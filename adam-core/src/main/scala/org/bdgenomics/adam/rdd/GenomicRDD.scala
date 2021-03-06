/**
 * Licensed to Big Data Genomics (BDG) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The BDG licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.bdgenomics.adam.rdd

import java.nio.file.Paths
import htsjdk.samtools.ValidationStringency
import htsjdk.variant.vcf.{
  VCFFilterHeaderLine,
  VCFFormatHeaderLine,
  VCFHeaderLine,
  VCFInfoHeaderLine,
  VCFHeaderLineCount,
  VCFHeaderLineType
}
import org.apache.avro.Schema
import org.apache.avro.file.DataFileWriter
import org.apache.avro.generic.IndexedRecord
import org.apache.avro.specific.{ SpecificDatumWriter, SpecificRecordBase }
import org.apache.hadoop.fs.{ FileSystem, Path }
import org.apache.hadoop.mapreduce.{ OutputFormat => NewOutputFormat }
import org.apache.parquet.avro.AvroParquetOutputFormat
import org.apache.parquet.hadoop.ParquetOutputFormat
import org.apache.parquet.hadoop.metadata.CompressionCodecName
import org.apache.parquet.hadoop.util.ContextUtil
import org.apache.spark.{ SparkContext, SparkFiles }
import org.apache.spark.api.java.JavaRDD
import org.apache.spark.api.java.function.{ Function => JFunction, Function2 }
import org.apache.spark.broadcast.Broadcast
import org.apache.spark.rdd.{ InstrumentedOutputFormat, RDD }
import org.apache.spark.sql.{ DataFrame, Dataset, SQLContext }
import org.apache.spark.sql.functions._
import org.apache.spark.storage.StorageLevel
import org.bdgenomics.adam.instrumentation.Timers._
import org.bdgenomics.adam.models.{
  RecordGroup,
  RecordGroupDictionary,
  ReferenceRegion,
  SequenceDictionary,
  SequenceRecord
}
import org.bdgenomics.adam.util.{ ManualRegionPartitioner, TextRddWriter }
import org.bdgenomics.formats.avro.{
  Contig,
  ProcessingStep,
  RecordGroup => RecordGroupMetadata,
  Sample
}
import org.bdgenomics.utils.cli.SaveArgs
import org.bdgenomics.utils.interval.array.IntervalArray
import org.bdgenomics.utils.misc.{ HadoopUtil, Logging }
import org.json4s.JsonDSL._
import org.json4s.jackson.JsonMethods._
import scala.annotation.tailrec
import scala.collection.JavaConversions._
import scala.math.{ floor => mathFloor, min }
import scala.reflect.ClassTag
import scala.reflect.runtime.universe._
import scala.util.Try

private[rdd] class JavaSaveArgs(var outputPath: String,
                                var blockSize: Int = 128 * 1024 * 1024,
                                var pageSize: Int = 1 * 1024 * 1024,
                                var compressionCodec: CompressionCodecName = CompressionCodecName.GZIP,
                                var disableDictionaryEncoding: Boolean = false,
                                var asSingleFile: Boolean = false,
                                var disableFastConcat: Boolean = false) extends ADAMSaveAnyArgs {
  var sortFastqOutput = false
  var deferMerging = false
}

private[rdd] object GenomicDataset {

  /**
   * Replaces file references in a command.
   *
   * @see pipe
   *
   * @param cmd Command to replace references in.
   * @param files List of paths to files.
   * @return Returns a command, with file paths subbed in.
   */
  def processCommand(cmd: Seq[String],
                     files: Seq[String]): List[String] = {
    val filesWithIndex: Seq[(String, String)] = files.zipWithIndex
      .map(p => {
        val (file, index) = p
        ("$%d".format(index), file)
      }).reverse
    val rootPath: (String, String) = ("$root",
      Paths.get(SparkFiles.getRootDirectory())
      .toAbsolutePath.toString)
    val filesAndPath: Seq[(String, String)] = filesWithIndex ++ Seq(rootPath)

    @tailrec def replaceEscapes(cmd: String,
                                iter: Iterator[(String, String)]): String = {
      if (!iter.hasNext) {
        cmd
      } else {
        val (idx, file) = iter.next
        val newCmd = cmd.replace(idx, file)
        replaceEscapes(newCmd, iter)
      }
    }

    cmd.map(s => {
      replaceEscapes(s, filesAndPath.toIterator)
    }).toList
  }
}

/**
 * A trait that wraps an RDD of genomic data with helpful metadata.
 *
 * @tparam T The type of the data in the wrapped RDD.
 * @tparam U The type of this GenomicDataset.
 */
trait GenomicDataset[T, U <: Product, V <: GenomicDataset[T, U, V]] extends Logging {

  val uTag: TypeTag[U]

  /**
   * These data as a Spark SQL Dataset.
   */
  val dataset: Dataset[U]

  protected val productFn: T => U
  protected val unproductFn: U => T

  /**
   * @return This data as a Spark SQL DataFrame.
   */
  def toDF(): DataFrame = {
    dataset.toDF()
  }

  /**
   * Applies a function that transforms the underlying Dataset into a new Dataset
   * using the Spark SQL API.
   *
   * @param tFn A function that transforms the underlying RDD as a Dataset.
   * @return A new RDD where the RDD of genomic data has been replaced, but the
   *   metadata (sequence dictionary, and etc) are copied without modification.
   */
  def transformDataset(tFn: Dataset[U] => Dataset[U]): V

  /**
   * Applies a function that transforms the underlying DataFrame into a new DataFrame
   * using the Spark SQL API.
   *
   * @param tFn A function that transforms the underlying RDD as a DataFrame.
   * @return A new RDD where the RDD of genomic data has been replaced, but the
   *   metadata (sequence dictionary, and etc) are copied without modification.
   */
  def transformDataFrame(tFn: DataFrame => DataFrame)(
    implicit uTag: TypeTag[U]): V = {
    val sqlContext = SQLContext.getOrCreate(rdd.context)
    import sqlContext.implicits._
    transformDataset((ds: Dataset[U]) => {
      tFn(ds.toDF()).as[U]
    })
  }

  /**
   * Applies a function that transforms the underlying DataFrame into a new DataFrame
   * using the Spark SQL API. Java-friendly variant.
   *
   * @param tFn A function that transforms the underlying RDD as a DataFrame.
   * @return A new RDD where the RDD of genomic data has been replaced, but the
   *   metadata (sequence dictionary, and etc) are copied without modification.
   */
  def transformDataFrame(tFn: JFunction[DataFrame, DataFrame]): V = {
    val sqlContext = SQLContext.getOrCreate(rdd.context)
    import sqlContext.implicits._
    transformDataFrame(tFn.call(_))(uTag)
  }

  /**
   * Applies a function that transmutes the underlying RDD into a new RDD of a
   * different type.
   *
   * @param tFn A function that transforms the underlying RDD.
   * @return A new RDD where the RDD of genomic data has been replaced, but the
   *   metadata (sequence dictionary, and etc) are copied without modification.
   */
  def transmuteDataset[X, Y <: Product, Z <: GenomicDataset[X, Y, Z]](
    tFn: Dataset[U] => Dataset[Y])(
      implicit yTag: TypeTag[Y],
      convFn: (V, Dataset[Y]) => Z): Z = {
    convFn(this.asInstanceOf[V], tFn(dataset))
  }

  /**
   * Applies a function that transmutes the underlying RDD into a new RDD of a
   * different type. Java friendly variant.
   *
   * @param tFn A function that transforms the underlying RDD.
   * @return A new RDD where the RDD of genomic data has been replaced, but the
   *   metadata (sequence dictionary, and etc) are copied without modification.
   */
  def transmuteDataset[X, Y <: Product, Z <: GenomicDataset[X, Y, Z]](
    tFn: JFunction[Dataset[U], Dataset[Y]],
    convFn: GenomicDatasetConversion[T, U, V, X, Y, Z]): Z = {
    val tfn: Dataset[U] => Dataset[Y] = tFn.call(_)
    val cfn: (V, Dataset[Y]) => Z = convFn.call(_, _)
    transmuteDataset[X, Y, Z](tfn)(convFn.yTag, cfn)
  }

  /**
   * Applies a function that transmutes the underlying RDD into a new RDD of a
   * different type. Java friendly variant.
   *
   * @param tFn A function that transforms the underlying RDD.
   * @return A new RDD where the RDD of genomic data has been replaced, but the
   *   metadata (sequence dictionary, and etc) are copied without modification.
   */
  def transmuteDataFrame[X, Y <: Product, Z <: GenomicDataset[X, Y, Z]](
    tFn: DataFrame => DataFrame)(
      implicit yTag: TypeTag[Y],
      convFn: (V, Dataset[Y]) => Z): Z = {
    val sqlContext = SQLContext.getOrCreate(rdd.context)
    import sqlContext.implicits._
    transmuteDataset[X, Y, Z]((ds: Dataset[U]) => {
      tFn(ds.toDF()).as[Y]
    })
  }

  /**
   * Applies a function that transmutes the underlying RDD into a new RDD of a
   * different type. Java friendly variant.
   *
   * @param tFn A function that transforms the underlying RDD.
   * @return A new RDD where the RDD of genomic data has been replaced, but the
   *   metadata (sequence dictionary, and etc) are copied without modification.
   */
  def transmuteDataFrame[X, Y <: Product, Z <: GenomicDataset[X, Y, Z]](
    tFn: JFunction[DataFrame, DataFrame],
    convFn: GenomicDatasetConversion[T, U, V, X, Y, Z]): Z = {
    val sqlContext = SQLContext.getOrCreate(rdd.context)
    import sqlContext.implicits._
    transmuteDataFrame[X, Y, Z](tFn.call(_))(convFn.yTag,
      (v: V, dsY: Dataset[Y]) => {
        convFn.call(v, dsY)
      })
  }

  override def toString = "%s with %d reference sequences"
    .format(getClass.getSimpleName, sequences.size)

  /**
   * Saves Avro data to a Hadoop file system.
   *
   * This method uses a SparkContext to identify our underlying file system,
   * which we then save to.
   *
   * Frustratingly enough, although all records generated by the Avro IDL
   * compiler have a static SCHEMA$ field, this field does not belong to
   * the SpecificRecordBase abstract class, or the SpecificRecord interface.
   * As such, we must force the user to pass in the schema.
   *
   * @tparam U The type of the specific record we are saving.
   * @param pathName Path to save records to.
   * @param sc SparkContext used for identifying underlying file system.
   * @param schema Schema of records we are saving.
   * @param avro Seq of records we are saving.
   */
  protected def saveAvro[U <: SpecificRecordBase](pathName: String,
                                                  sc: SparkContext,
                                                  schema: Schema,
                                                  avro: Seq[U])(implicit tUag: ClassTag[U]) {

    // get our current file system
    val path = new Path(pathName)
    val fs = path.getFileSystem(sc.hadoopConfiguration)

    // get an output stream
    val os = fs.create(path)

    // set up avro for writing
    val dw = new SpecificDatumWriter[U](schema)
    val fw = new DataFileWriter[U](dw)
    fw.create(schema, os)

    // write all our records
    avro.foreach(r => fw.append(r))

    // close the file
    fw.close()
    os.close()
  }

  /**
   * Saves an RDD to Parquet.
   *
   * @param args The output format configuration to use when saving the data.
   */
  def saveAsParquet(args: SaveArgs): Unit = {
    saveAsParquet(args.outputPath,
      args.blockSize,
      args.pageSize,
      args.compressionCodec,
      args.disableDictionaryEncoding)
  }

  /**
   * Saves an RDD of Avro data to Parquet.
   *
   * @param pathName The path to save the file to.
   * @param blockSize The size in bytes of blocks to write.
   * @param pageSize The size in bytes of pages to write.
   * @param compressCodec The compression codec to apply to pages.
   * @param disableDictionaryEncoding If false, dictionary encoding is used. If
   *   true, delta encoding is used.
   */
  def saveAsParquet(
    pathName: String,
    blockSize: Int = 128 * 1024 * 1024,
    pageSize: Int = 1 * 1024 * 1024,
    compressCodec: CompressionCodecName = CompressionCodecName.GZIP,
    disableDictionaryEncoding: Boolean = false): Unit

  /**
   * The RDD of genomic data that we are wrapping.
   */
  val rdd: RDD[T]

  /**
   * The sequence dictionary describing the reference assembly this dataset is
   * aligned to.
   */
  val sequences: SequenceDictionary

  /**
   * Replaces the sequence dictionary attached to a GenomicDataset.
   *
   * @param newSequences The new sequence dictionary to attach.
   * @return Returns a new GenomicDataset with the sequences replaced.
   */
  def replaceSequences(newSequences: SequenceDictionary): V

  /**
   * Caches underlying RDD in memory.
   *
   * @return Cached GenomicDataset.
   */
  def cache(): V = {
    replaceRdd(rdd.cache())
  }

  /**
   * Persists underlying RDD in memory or disk.
   *
   * @param sl new StorageLevel
   * @return Persisted GenomicDataset.
   */
  def persist(sl: StorageLevel): V = {
    replaceRdd(rdd.persist(sl))
  }

  /**
   * Unpersists underlying RDD from memory or disk.
   *
   * @return Uncached GenomicDataset.
   */
  def unpersist(): V = {
    replaceRdd(rdd.unpersist())
  }

  /**
   * Appends sequence metadata to the current RDD.
   *
   * @param sequencesToAdd The new sequences to append.
   * @return Returns a new GenomicDataset with the sequences appended.
   */
  def addSequences(sequencesToAdd: SequenceDictionary): V = {
    replaceSequences(sequences ++ sequencesToAdd)
  }

  /**
   * Appends metadata for a single sequence to the current RDD.
   *
   * @param sequenceToAdd The sequence to add.
   * @return Returns a new GenomicDataset with this sequence appended.
   */
  def addSequence(sequenceToAdd: SequenceRecord): V = {
    addSequences(SequenceDictionary(sequenceToAdd))
  }

  /**
   * The underlying RDD of genomic data, as a JavaRDD.
   */
  lazy val jrdd: JavaRDD[T] = {
    rdd.toJavaRDD()
  }

  /**
   * Save the sequence dictionary to disk.
   *
   * @param filePath The filepath where we will save the sequence dictionary.
   */
  protected def saveSequences(filePath: String): Unit = {
    // convert sequence dictionary to avro form and save
    val contigs = sequences.toAvro

    saveAvro("%s/_seqdict.avro".format(filePath),
      rdd.context,
      Contig.SCHEMA$,
      contigs)
  }

  /**
   * Unions together multiple genomic RDDs.
   *
   * @param rdds RDDs to union with this RDD.
   */
  def union(rdds: V*): V

  /**
   * Unions together multiple genomic RDDs.
   *
   * @param rdds RDDs to union with this RDD.
   */
  def union(rdds: java.util.List[V]): V = {
    val rddSeq: Seq[V] = rdds.toSeq
    union(rddSeq: _*)
  }

  /**
   * Applies a function that transforms the underlying RDD into a new RDD.
   *
   * @param tFn A function that transforms the underlying RDD.
   * @return A new RDD where the RDD of genomic data has been replaced, but the
   *   metadata (sequence dictionary, and etc) are copied without modification.
   */
  def transform(tFn: RDD[T] => RDD[T]): V = {
    replaceRdd(tFn(rdd))
  }

  /**
   * Applies a function that transforms the underlying RDD into a new RDD.
   *
   * @param tFn A function that transforms the underlying RDD.
   * @return A new RDD where the RDD of genomic data has been replaced, but the
   *   metadata (sequence dictionary, and etc) are copied without modification.
   */
  def transform(tFn: JFunction[JavaRDD[T], JavaRDD[T]]): V = {
    replaceRdd(tFn.call(jrdd).rdd)
  }

  /**
   * Applies a function that transmutes the underlying RDD into a new RDD of a
   * different type.
   *
   * @param tFn A function that transforms the underlying RDD.
   * @return A new RDD where the RDD of genomic data has been replaced, but the
   *   metadata (sequence dictionary, and etc) are copied without modification.
   */
  def transmute[X, Y <: Product, Z <: GenomicDataset[X, Y, Z]](tFn: RDD[T] => RDD[X])(
    implicit convFn: (V, RDD[X]) => Z): Z = {
    convFn(this.asInstanceOf[V], tFn(rdd))
  }

  /**
   * Applies a function that transmutes the underlying RDD into a new RDD of a
   * different type. Java friendly version.
   *
   * @param tFn A function that transforms the underlying RDD.
   * @param convFn The conversion function used to build the final RDD.
   * @return A new RDD where the RDD of genomic data has been replaced, but the
   *   metadata (sequence dictionary, and etc) are copied without modification.
   */
  def transmute[X, Y <: Product, Z <: GenomicDataset[X, Y, Z]](
    tFn: JFunction[JavaRDD[T], JavaRDD[X]],
    convFn: Function2[V, RDD[X], Z]): Z = {
    convFn.call(this.asInstanceOf[V], tFn.call(jrdd).rdd)
  }

  // The partition map is structured as follows:
  // The outer option is for whether or not there is a partition map.
  //   - This is None in the case that we don't know the bounds on each 
  //     partition.
  // The Array is the length of the number of partitions.
  // The inner option is in case there is no data on a partition.
  // The (ReferenceRegion, ReferenceRegion) tuple contains the bounds of the 
  //   partition, such that the lowest start is first and the highest end is
  //   second.
  protected val optPartitionMap: Option[Array[Option[(ReferenceRegion, ReferenceRegion)]]]

  assert(optPartitionMap == null ||
    optPartitionMap.isEmpty ||
    optPartitionMap.exists(_.length == rdd.partitions.length),
    "Partition map length differs from number of partitions.")

  def isSorted: Boolean = optPartitionMap.isDefined

  /**
   * Repartitions all data in rdd and distributes it as evenly as possible
   * into the number of partitions provided.
   *
   * @param partitions the number of partitions to repartition this rdd into
   * @return a new repartitioned GenomicDataset
   */
  private[rdd] def evenlyRepartition(partitions: Int)(implicit tTag: ClassTag[T]): V = {
    require(isSorted, "Cannot evenly repartition an unsorted RDD.")
    val count = rdd.count
    // we don't want a bunch of empty partitions, so we will just use count in
    // the case the user wants more partitions than rdd records.
    val finalPartitionNumber = min(count, partitions)
    // the average number of records on each node will help us evenly repartition
    val average = count.toDouble / finalPartitionNumber

    val finalPartitionedRDD =
      flattenRddByRegions()
        .zipWithIndex
        .mapPartitions(iter => {
          // divide the global index by the average to get the destination
          // partition number
          iter.map(_.swap).map(f =>
            ((f._2._1, (f._1 / average).toInt), f._2._2))
        }, preservesPartitioning = true)
        .repartitionAndSortWithinPartitions(
          ManualRegionPartitioner(finalPartitionNumber.toInt))

    val newPartitionMap = finalPartitionedRDD.mapPartitions(iter =>
      getRegionBoundsFromPartition(
        iter.map(f => (f._1._1, f._2))),
      preservesPartitioning = true).collect

    replaceRdd(finalPartitionedRDD.values, Some(newPartitionMap))
  }

  /**
   * If the specified validation strategy is STRICT, throw an exception,
   * if LENIENT, log a warning, otherwise does nothing.
   *
   * @throws IllegalArgumentException If stringency is STRICT.
   *
   * @param message The error or warning message.
   * @param stringency The validation stringency.
   */
  private def throwWarnOrNone[K](message: String,
                                 stringency: ValidationStringency): Option[K] = {
    stringency match {
      case ValidationStringency.STRICT => {
        throw new IllegalArgumentException(message)
      }
      case ValidationStringency.LENIENT => log.warn(message)
      case _                            =>
    }
    None
  }

  /**
   * Sorts our genome aligned data by reference positions, with contigs ordered
   * by index.
   *
   * @return Returns a new RDD containing sorted data.
   *
   * @see sortLexicographically
   */
  def sort(): V = {
    sort(partitions = rdd.partitions.length,
      stringency = ValidationStringency.STRICT)(ClassTag.AnyRef.asInstanceOf[ClassTag[T]])
  }

  /**
   * Sorts our genome aligned data by reference positions, with contigs ordered
   * by index.
   *
   * @param partitions The number of partitions for the new RDD.
   * @param stringency The level of ValidationStringency to enforce.
   * @return Returns a new RDD containing sorted data.
   *
   * @note Uses ValidationStringency to handle unaligned or where objects align
   *   to multiple positions.
   * @see sortLexicographically
   */
  def sort(partitions: Int = rdd.partitions.length,
           stringency: ValidationStringency = ValidationStringency.STRICT)(
             implicit tTag: ClassTag[T]): V = {

    require(sequences.hasSequenceOrdering,
      "Sequence Dictionary does not have ordering defined.")

    replaceRdd(rdd.flatMap(elem => {
      val coveredRegions = getReferenceRegions(elem)

      // We don't use ValidationStringency here because multimapped elements
      // break downstream methods.
      require(coveredRegions.size <= 1,
        "Cannot sort RDD containing a multimapped element. %s covers %s.".format(
          elem, coveredRegions.mkString(",")))

      if (coveredRegions.isEmpty) {
        throwWarnOrNone[((Int, Long), T)](
          "Cannot sort RDD containing an unmapped element %s.".format(elem),
          stringency)
      } else {
        val contigName = coveredRegions.head.referenceName
        val sr = sequences(contigName)

        if (sr.isEmpty) {
          throwWarnOrNone[((Int, Long), T)](
            "Element %s has contig name %s not in dictionary %s.".format(
              elem, contigName, sequences),
            stringency)
        } else {
          Some(((sr.get.referenceIndex.get, coveredRegions.head.start), elem))
        }
      }
    }).sortByKey(ascending = true, numPartitions = partitions)
      .values)
  }

  /**
   * Sorts our genome aligned data by reference positions, with contigs ordered
   * lexicographically.
   *
   * @return Returns a new RDD containing sorted data.
   *
   * @see sort
   */
  def sortLexicographically(): V = {
    sortLexicographically(storePartitionMap = false)(ClassTag.AnyRef.asInstanceOf[ClassTag[T]])
  }

  /**
   * Sorts our genome aligned data by reference positions, with contigs ordered
   * lexicographically.
   *
   * @param partitions The number of partitions for the new RDD.
   * @param storePartitionMap A Boolean flag to determine whether to store the
   *                          partition bounds from the resulting RDD.
   * @param storageLevel The level at which to persist the resulting RDD.
   * @param stringency The level of ValidationStringency to enforce.
   * @return Returns a new RDD containing sorted data.
   *
   * @note Uses ValidationStringency to handle data that is unaligned or where objects
   *   align to multiple positions.
   * @see sort
   */
  def sortLexicographically(partitions: Int = rdd.partitions.length,
                            storePartitionMap: Boolean = false,
                            storageLevel: StorageLevel = StorageLevel.MEMORY_ONLY,
                            stringency: ValidationStringency = ValidationStringency.STRICT)(
                              implicit tTag: ClassTag[T]): V = {

    val partitionedRdd = rdd.flatMap(elem => {
      val coveredRegions = getReferenceRegions(elem)

      // We don't use ValidationStringency here because multimapped elements
      // break downstream methods.
      require(coveredRegions.size <= 1,
        "Cannot sort RDD containing a multimapped element. %s covers %s.".format(
          elem, coveredRegions.mkString(",")))

      if (coveredRegions.isEmpty) {
        throwWarnOrNone[(ReferenceRegion, T)](
          "Cannot sort RDD containing an unmapped element %s.".format(elem),
          stringency)
      } else {
        Some(coveredRegions.head, elem)
      }
    }).sortByKey(ascending = true, numPartitions = partitions)

    partitionedRdd.persist(storageLevel)

    storePartitionMap match {
      case true => {
        val newPartitionMap = partitionedRdd.mapPartitions(iter =>
          getRegionBoundsFromPartition(iter), preservesPartitioning = true).collect

        replaceRdd(partitionedRdd.values, Some(newPartitionMap))
      }
      case false => {
        replaceRdd(partitionedRdd.values)
      }
    }
  }

  /**
   * Pipes genomic data to a subprocess that runs in parallel using Spark.
   *
   * Files are substituted in to the command with a $x syntax. E.g., to invoke
   * a command that uses the first file from the files Seq, use $0. To access
   * the path to the directory where the files are copied, use $root.
   *
   * Pipes require the presence of an InFormatterCompanion and an OutFormatter
   * as implicit values. The InFormatterCompanion should be a singleton whose
   * apply method builds an InFormatter given a specific type of GenomicDataset.
   * The implicit InFormatterCompanion yields an InFormatter which is used to
   * format the input to the pipe, and the implicit OutFormatter is used to
   * parse the output from the pipe.
   *
   * @param cmd Command to run.
   * @param files Files to make locally available to the commands being run.
   *   Default is empty.
   * @param environment A map containing environment variable/value pairs to set
   *   in the environment for the newly created process. Default is empty.
   * @param flankSize Number of bases to flank each command invocation by.
   * @param optTimeout An optional parameter specifying how long to let a single
   *   partition run for, in seconds. If the partition times out, the partial
   *   results will be returned, and no exception will be logged. The partition
   *   will log that the command timed out.
   * @return Returns a new GenomicDataset of type Y.
   *
   * @tparam X The type of the record created by the piped command.
   * @tparam Y A GenomicDataset containing X's.
   * @tparam V The InFormatter to use for formatting the data being piped to the
   *   command.
   */
  def pipe[X, Y <: Product, Z <: GenomicDataset[X, Y, Z], W <: InFormatter[T, U, V, W]](
    cmd: Seq[String],
    files: Seq[String] = Seq.empty,
    environment: Map[String, String] = Map.empty,
    flankSize: Int = 0,
    optTimeout: Option[Int] = None)(implicit tFormatterCompanion: InFormatterCompanion[T, U, V, W],
                                    xFormatter: OutFormatter[X],
                                    convFn: (V, RDD[X]) => Z,
                                    tManifest: ClassTag[T],
                                    xManifest: ClassTag[X]): Z = {

    // TODO: support broadcasting files
    files.foreach(f => {
      rdd.context.addFile(f)
    })

    // make formatter
    val tFormatter: W = tFormatterCompanion.apply(this.asInstanceOf[V])

    // make bins
    val seqLengths = sequences.records.toSeq.map(rec => (rec.name, rec.length)).toMap
    val totalLength = seqLengths.values.sum
    val bins = GenomeBins(totalLength / rdd.partitions.size, seqLengths)

    // if the input rdd is mapped, then we need to repartition
    val partitionedRdd = if (sequences.records.size > 0) {
      // get region covered, expand region by flank size, and tag with bins
      val binKeyedRdd = rdd.flatMap(r => {

        // get regions and expand
        val regions = getReferenceRegions(r).map(_.pad(flankSize))

        // get all the bins this record falls into
        val recordBins = regions.flatMap(rr => {
          (bins.getStartBin(rr) to bins.getEndBin(rr)).map(b => (rr, b))
        })

        // key the record by those bins and return
        // TODO: this should key with the reference region corresponding to a bin
        recordBins.map(b => (b, r))
      })

      // repartition yonder our data
      binKeyedRdd
        .repartitionAndSortWithinPartitions(
          ManualRegionPartitioner(bins.numBins))
        .values
    } else {
      rdd
    }

    // are we in local mode?
    val isLocal = partitionedRdd.context.isLocal

    // call map partitions and pipe
    val pipedRdd = partitionedRdd.mapPartitions(iter => {
      if (iter.hasNext) {

        val locs = if (isLocal) {
          files.map(f => {
            // SparkFiles.getRootDirectory is set in local mode even if driverTmpDir is not
            val root = Paths.get(SparkFiles.getRootDirectory()).toAbsolutePath.toString
            val fileName = new Path(f).getName()
            Paths.get(root, fileName).toString
          })
        } else {
          files.map(f => {
            SparkFiles.get(new Path(f).getName())
          })
        }

        // replace file references in command and create process builder
        val finalCmd = GenomicDataset.processCommand(cmd, locs)
        val pb = new ProcessBuilder(finalCmd)
        pb.redirectError(ProcessBuilder.Redirect.INHERIT)

        // add environment variables to the process builder
        val pEnv = pb.environment()
        environment.foreach(kv => {
          val (k, v) = kv
          pEnv.put(k, v)
        })

        // start underlying piped command
        val process = pb.start()
        val os = process.getOutputStream()
        val is = process.getInputStream()

        // wrap in formatter and run as a thread
        val ifr = new InFormatterRunner[T, U, V, W](iter, tFormatter, os)
        new Thread(ifr).start()

        // wrap out formatter
        new OutFormatterRunner[X, OutFormatter[X]](xFormatter,
          is,
          process,
          finalCmd,
          optTimeout)
      } else {
        Iterator[X]()
      }
    })

    // build the new GenomicDataset
    val newRdd = convFn(this.asInstanceOf[V], pipedRdd)

    // if the original rdd was aligned and the final rdd is aligned, then we must filter
    if (newRdd.sequences.isEmpty ||
      sequences.isEmpty) {
      newRdd
    } else {
      def filterPartition(idx: Int, iter: Iterator[X]): Iterator[X] = {

        // get the region for this partition
        val region = bins.invert(idx)

        // map over the iterator and filter out any items that don't belong
        iter.filter(x => {

          // get the regions for x
          val regions = newRdd.getReferenceRegions(x)

          // are there any regions that overlap our current region
          !regions.forall(!_.overlaps(region))
        })
      }

      // run a map partitions with index and discard all items that fall
      // outside of their own partition's region bound
      newRdd.transform(_.mapPartitionsWithIndex(filterPartition))
    }
  }

  /**
   * Pipes genomic data to a subprocess that runs in parallel using Spark.
   *
   * SparkR friendly variant.
   *
   * @param cmd Command to run.
   * @param files Files to make locally available to the commands being run.
   *   Default is empty.
   * @param environment A map containing environment variable/value pairs to set
   *   in the environment for the newly created process. Default is empty.
   * @param flankSize Number of bases to flank each command invocation by.
   * @param tFormatter Class of formatter for data going into pipe command.
   * @param xFormatter Formatter for data coming out of the pipe command.
   * @param convFn The conversion function used to build the final RDD.
   * @return Returns a new GenomicDataset of type Y.
   *
   * @tparam X The type of the record created by the piped command.
   * @tparam Y A GenomicDataset containing X's.
   * @tparam V The InFormatter to use for formatting the data being piped to the
   *   command.
   */
  def pipe[X, Y <: Product, Z <: GenomicDataset[X, Y, Z], W <: InFormatter[T, U, V, W]](
    cmd: Seq[Any],
    files: Seq[Any],
    environment: java.util.Map[Any, Any],
    flankSize: java.lang.Double,
    tFormatter: Class[W],
    xFormatter: OutFormatter[X],
    convFn: Function2[V, RDD[X], Z]): Z = {
    val jInt: java.lang.Integer = flankSize.toInt
    pipe[X, Y, Z, W](cmd.asInstanceOf[Seq[String]].toList,
      files.asInstanceOf[Seq[String]].toList,
      environment.asInstanceOf[java.util.Map[String, String]],
      jInt,
      tFormatter,
      xFormatter,
      convFn)
  }

  /**
   * Pipes genomic data to a subprocess that runs in parallel using Spark.
   *
   * Java/PySpark friendly variant.
   *
   * @param cmd Command to run.
   * @param files Files to make locally available to the commands being run.
   *   Default is empty.
   * @param environment A map containing environment variable/value pairs to set
   *   in the environment for the newly created process. Default is empty.
   * @param flankSize Number of bases to flank each command invocation by.
   * @param tFormatter Class of formatter for data going into pipe command.
   * @param xFormatter Formatter for data coming out of the pipe command.
   * @param convFn The conversion function used to build the final RDD.
   * @return Returns a new GenomicDataset of type Y.
   *
   * @tparam X The type of the record created by the piped command.
   * @tparam Y A GenomicDataset containing X's.
   * @tparam V The InFormatter to use for formatting the data being piped to the
   *   command.
   */
  def pipe[X, Y <: Product, Z <: GenomicDataset[X, Y, Z], W <: InFormatter[T, U, V, W]](
    cmd: java.util.List[String],
    files: java.util.List[String],
    environment: java.util.Map[String, String],
    flankSize: java.lang.Integer,
    tFormatter: Class[W],
    xFormatter: OutFormatter[X],
    convFn: Function2[V, RDD[X], Z]): Z = {

    // get companion object for in formatter
    val tFormatterCompanion = {
      val companionType = try {
        tFormatter.getMethod("companion")
          .getReturnType
      } catch {
        case e: Throwable => {
          throw new IllegalArgumentException(
            "Failed to get companion apply method for user provided InFormatter (%s). Exception was: %s.".format(
              tFormatter.getName,
              e))
        }
      }

      val tFormatterCompanionConstructors = companionType.getDeclaredConstructors()
      val tFormatterCompanionConstructor = tFormatterCompanionConstructors.head
      tFormatterCompanionConstructor.setAccessible(true)

      tFormatterCompanionConstructor.newInstance()
        .asInstanceOf[InFormatterCompanion[T, U, V, W]]
    }

    pipe[X, Y, Z, W](cmd, files.toSeq, environment.toMap, flankSize)(
      tFormatterCompanion,
      xFormatter,
      (gRdd: V, rdd: RDD[X]) => convFn.call(gRdd, rdd),
      ClassTag.AnyRef.asInstanceOf[ClassTag[T]],
      ClassTag.AnyRef.asInstanceOf[ClassTag[X]])
  }

  protected def replaceRdd(
    newRdd: RDD[T],
    newPartitionMap: Option[Array[Option[(ReferenceRegion, ReferenceRegion)]]] = None): V

  protected def getReferenceRegions(elem: T): Seq[ReferenceRegion]

  protected def flattenRddByRegions(): RDD[(ReferenceRegion, T)] = {
    rdd.flatMap(elem => {
      getReferenceRegions(elem).map(r => (r, elem))
    })
  }

  /**
   * Runs a filter that selects data in the underlying RDD that overlaps a
   * single genomic region.
   *
   * @param query The region to query for.
   * @return Returns a new GenomicDataset containing only data that overlaps the
   *   query region.
   */
  def filterByOverlappingRegion(query: ReferenceRegion): V = {
    replaceRdd(rdd.filter(elem => {

      // where can this item sit?
      val regions = getReferenceRegions(elem)

      // do any of these overlap with our query region?
      regions.exists(_.overlaps(query))
    }), optPartitionMap)
  }

  /**
   * Runs a filter that selects data in the underlying RDD that overlaps
   * several genomic regions.
   *
   * @param querys The regions to query for.
   * @return Returns a new GenomicDataset containing only data that overlaps the
   *   querys region.
   */
  def filterByOverlappingRegions(querys: Iterable[ReferenceRegion]): V = {
    replaceRdd(rdd.filter(elem => {

      val regions = getReferenceRegions(elem)

      querys.exists(query => {
        regions.exists(_.overlaps(query))
      })
    }), optPartitionMap)
  }

  /**
   * Runs a filter that selects data in the underlying RDD that overlaps
   * several genomic regions. Java friendly version.
   *
   * @param querys The regions to query for.
   * @return Returns a new GenomicRDD containing only data that overlaps the
   *   querys region.
   */
  def filterByOverlappingRegions(querys: java.lang.Iterable[ReferenceRegion]): V = {
    replaceRdd(rdd.filter(elem => {

      val regions = getReferenceRegions(elem)

      querys.exists(query => {
        regions.exists(_.overlaps(query))
      })
    }), optPartitionMap)
  }

  protected def buildTree(
    rdd: RDD[(ReferenceRegion, T)])(
      implicit tTag: ClassTag[T]): IntervalArray[ReferenceRegion, T]

  def broadcast()(
    implicit tTag: ClassTag[T]): GenomicBroadcast[T, U, V] = {
    GenomicBroadcast[T, U, V](this.asInstanceOf[V],
      rdd.context.broadcast(buildTree(flattenRddByRegions())))
  }

  /**
   * Performs a broadcast inner join between this RDD and another RDD.
   *
   * In a broadcast join, the left RDD (this RDD) is collected to the driver,
   * and broadcast to all the nodes in the cluster. The key equality function
   * used for this join is the reference region overlap function. Since this
   * is an inner join, all values who do not overlap a value from the other
   * RDD are dropped. SparkR friendly version.
   *
   * @param genomicRdd The right RDD in the join.
   * @param flankSize Sets a flankSize for the distance between elements to be
   *   joined. If set to 0, an overlap is required to join two elements.
   * @return Returns a new genomic RDD containing all pairs of keys that
   *   overlapped in the genomic coordinate space.
   */
  def broadcastRegionJoin[X, Y <: Product, Z <: GenomicDataset[X, Y, Z]](
    genomicRdd: GenomicDataset[X, Y, Z],
    flankSize: java.lang.Double): GenericGenomicDataset[(T, X), (U, Y)] = {

    broadcastRegionJoin(genomicRdd, flankSize.toInt: java.lang.Integer)
  }

  /**
   * Performs a broadcast inner join between this RDD and another RDD.
   *
   * In a broadcast join, the left RDD (this RDD) is collected to the driver,
   * and broadcast to all the nodes in the cluster. The key equality function
   * used for this join is the reference region overlap function. Since this
   * is an inner join, all values who do not overlap a value from the other
   * RDD are dropped. Python/Java friendly version.
   *
   * @param genomicRdd The right RDD in the join.
   * @param flankSize Sets a flankSize for the distance between elements to be
   *   joined. If set to 0, an overlap is required to join two elements.
   * @return Returns a new genomic RDD containing all pairs of keys that
   *   overlapped in the genomic coordinate space.
   */
  def broadcastRegionJoin[X, Y <: Product, Z <: GenomicDataset[X, Y, Z]](
    genomicRdd: GenomicDataset[X, Y, Z],
    flankSize: java.lang.Integer): GenericGenomicDataset[(T, X), (U, Y)] = {

    implicit val tTag = ClassTag.AnyRef.asInstanceOf[ClassTag[T]]
    implicit val xTag = ClassTag.AnyRef.asInstanceOf[ClassTag[X]]
    implicit val txTag = ClassTag.AnyRef.asInstanceOf[ClassTag[(T, X)]]
    implicit val u1Tag: TypeTag[U] = uTag
    implicit val u2Tag: TypeTag[Y] = genomicRdd.uTag
    implicit val uyTag = typeTag[(U, Y)]

    broadcastRegionJoin(genomicRdd, flankSize.toLong)
  }

  /**
   * Performs a broadcast inner join between this RDD and another RDD.
   *
   * In a broadcast join, the left RDD (this RDD) is collected to the driver,
   * and broadcast to all the nodes in the cluster. The key equality function
   * used for this join is the reference region overlap function. Since this
   * is an inner join, all values who do not overlap a value from the other
   * RDD are dropped.
   *
   * @param genomicRdd The right RDD in the join.
   * @param flankSize Sets a flankSize for the distance between elements to be
   *   joined. If set to 0, an overlap is required to join two elements.
   * @return Returns a new genomic RDD containing all pairs of keys that
   *   overlapped in the genomic coordinate space.
   *
   * @see broadcastRegionJoinAgainst
   */
  def broadcastRegionJoin[X, Y <: Product, Z <: GenomicDataset[X, Y, Z]](
    genomicRdd: GenomicDataset[X, Y, Z],
    flankSize: Long)(
      implicit tTag: ClassTag[T],
      xTag: ClassTag[X],
      txTag: ClassTag[(T, X)],
      uyTag: TypeTag[(U, Y)]): GenericGenomicDataset[(T, X), (U, Y)] = InnerBroadcastJoin.time {

    // key the RDDs and join
    RDDBoundGenericGenomicDataset[(T, X), (U, Y)](InnerTreeRegionJoin[T, X]().broadcastAndJoin(
      buildTree(flattenRddByRegions().map(f => (f._1.pad(flankSize), f._2))),
      genomicRdd.flattenRddByRegions()),
      sequences ++ genomicRdd.sequences,
      GenericConverter[(T, X), (U, Y)](kv => {
        // pad by -1 * flankSize to undo pad from preprocessing
        getReferenceRegions(kv._1).map(_.pad(-1 * flankSize)) ++
          genomicRdd.getReferenceRegions(kv._2)
      },
        kv => (productFn(kv._1), genomicRdd.productFn(kv._2)),
        kv => (unproductFn(kv._1), genomicRdd.unproductFn(kv._2))),
      TagHolder[(T, X), (U, Y)]())
  }

  /**
   * Performs a broadcast inner join between this RDD and another RDD.
   *
   * In a broadcast join, the left RDD (this RDD) is collected to the driver,
   * and broadcast to all the nodes in the cluster. The key equality function
   * used for this join is the reference region overlap function. Since this
   * is an inner join, all values who do not overlap a value from the other
   * RDD are dropped.
   *
   * @param genomicRdd The right RDD in the join.
   * @return Returns a new genomic RDD containing all pairs of keys that
   *   overlapped in the genomic coordinate space.
   *
   * @see broadcastRegionJoinAgainst
   */
  def broadcastRegionJoin[X, Y <: Product, Z <: GenomicDataset[X, Y, Z]](
    genomicRdd: GenomicDataset[X, Y, Z])(
      implicit tTag: ClassTag[T],
      xTag: ClassTag[X],
      txTag: ClassTag[(T, X)],
      uyTag: TypeTag[(U, Y)]): GenericGenomicDataset[(T, X), (U, Y)] = {

    broadcastRegionJoin(genomicRdd, 0L)
  }

  /**
   * Performs a broadcast inner join between this RDD and data that has been broadcast.
   *
   * In a broadcast join, the left side of the join (broadcastTree) is broadcast to
   * to all the nodes in the cluster. The key equality
   * function used for this join is the reference region overlap function. Since this
   * is an inner join, all values who do not overlap a value from the other
   * RDD are dropped. As compared to broadcastRegionJoin, this function allows the
   * broadcast object to be reused across multiple joins.
   *
   * @note This function differs from other region joins as it treats the calling RDD
   *   as the right side of the join, and not the left.
   *
   * @param broadcastTree The data on the left side of the join.
   * @return Returns a new genomic RDD containing all pairs of keys that
   *   overlapped in the genomic coordinate space.
   *
   * @see broadcastRegionJoin
   */
  def broadcastRegionJoinAgainst[X, Y <: Product, Z <: GenomicDataset[X, Y, Z]](
    broadcast: GenomicBroadcast[X, Y, Z])(
      implicit tTag: ClassTag[T], xTag: ClassTag[X],
      uyTag: TypeTag[(Y, U)]): GenericGenomicDataset[(X, T), (Y, U)] = InnerBroadcastJoin.time {

    // key the RDDs and join
    RDDBoundGenericGenomicDataset[(X, T), (Y, U)](InnerTreeRegionJoin[X, T]().join(
      broadcast.broadcastTree,
      flattenRddByRegions()),
      sequences ++ broadcast.backingDataset.sequences,
      GenericConverter[(X, T), (Y, U)](kv => {
        broadcast.backingDataset.getReferenceRegions(kv._1) ++
          getReferenceRegions(kv._2)
      },
        kv => (broadcast.backingDataset.productFn(kv._1), productFn(kv._2)),
        kv => (broadcast.backingDataset.unproductFn(kv._1), unproductFn(kv._2))),
      TagHolder[(X, T), (Y, U)]())
  }

  /**
   * Performs a broadcast right outer join between this RDD and another RDD.
   *
   * In a broadcast join, the left RDD (this RDD) is collected to the driver,
   * and broadcast to all the nodes in the cluster. The key equality function
   * used for this join is the reference region overlap function. Since this
   * is a right outer join, all values in the left RDD that do not overlap a
   * value from the right RDD are dropped. If a value from the right RDD does
   * not overlap any values in the left RDD, it will be paired with a `None`
   * in the product of the join. SparkR friendly version.
   *
   * @param genomicRdd The right RDD in the join.
   * @param flankSize Sets a flankSize for the distance between elements to be
   *   joined. If set to 0, an overlap is required to join two elements.
   * @return Returns a new genomic RDD containing all pairs of keys that
   *   overlapped in the genomic coordinate space, and all keys from the
   *   right RDD that did not overlap a key in the left RDD.
   */
  def rightOuterBroadcastRegionJoin[X, Y <: Product, Z <: GenomicDataset[X, Y, Z]](
    genomicRdd: GenomicDataset[X, Y, Z],
    flankSize: java.lang.Double): GenericGenomicDataset[(Option[T], X), (Option[U], Y)] = {

    rightOuterBroadcastRegionJoin(genomicRdd, flankSize.toInt: java.lang.Integer)
  }

  /**
   * Performs a broadcast right outer join between this RDD and another RDD.
   *
   * In a broadcast join, the left RDD (this RDD) is collected to the driver,
   * and broadcast to all the nodes in the cluster. The key equality function
   * used for this join is the reference region overlap function. Since this
   * is a right outer join, all values in the left RDD that do not overlap a
   * value from the right RDD are dropped. If a value from the right RDD does
   * not overlap any values in the left RDD, it will be paired with a `None`
   * in the product of the join. PySpark/Java friendly version.
   *
   * @param genomicRdd The right RDD in the join.
   * @param flankSize Sets a flankSize for the distance between elements to be
   *   joined. If set to 0, an overlap is required to join two elements.
   * @return Returns a new genomic RDD containing all pairs of keys that
   *   overlapped in the genomic coordinate space, and all keys from the
   *   right RDD that did not overlap a key in the left RDD.
   */
  def rightOuterBroadcastRegionJoin[X, Y <: Product, Z <: GenomicDataset[X, Y, Z]](
    genomicRdd: GenomicDataset[X, Y, Z],
    flankSize: java.lang.Integer): GenericGenomicDataset[(Option[T], X), (Option[U], Y)] = {
    implicit val tTag = ClassTag.AnyRef.asInstanceOf[ClassTag[T]]
    implicit val xTag = ClassTag.AnyRef.asInstanceOf[ClassTag[X]]
    implicit val txTag = ClassTag.AnyRef.asInstanceOf[ClassTag[(Option[T], X)]]
    implicit val u1Tag: TypeTag[U] = uTag
    implicit val u2Tag: TypeTag[Y] = genomicRdd.uTag
    implicit val uyTag = typeTag[(Option[U], Y)]

    rightOuterBroadcastRegionJoin(genomicRdd, flankSize.toLong)
  }

  /**
   * Performs a broadcast right outer join between this RDD and another RDD.
   *
   * In a broadcast join, the left RDD (this RDD) is collected to the driver,
   * and broadcast to all the nodes in the cluster. The key equality function
   * used for this join is the reference region overlap function. Since this
   * is a right outer join, all values in the left RDD that do not overlap a
   * value from the right RDD are dropped. If a value from the right RDD does
   * not overlap any values in the left RDD, it will be paired with a `None`
   * in the product of the join.
   *
   * @param genomicRdd The right RDD in the join.
   * @param flankSize Sets a flankSize for the distance between elements to be
   *   joined. If set to 0, an overlap is required to join two elements.
   * @return Returns a new genomic RDD containing all pairs of keys that
   *   overlapped in the genomic coordinate space, and all keys from the
   *   right RDD that did not overlap a key in the left RDD.
   *
   * @see rightOuterBroadcastRegionJoin
   */
  def rightOuterBroadcastRegionJoin[X, Y <: Product, Z <: GenomicDataset[X, Y, Z]](
    genomicRdd: GenomicDataset[X, Y, Z],
    flankSize: Long)(
      implicit tTag: ClassTag[T],
      xTag: ClassTag[X],
      otxTag: ClassTag[(Option[T], X)],
      ouyTag: TypeTag[(Option[U], Y)]): GenericGenomicDataset[(Option[T], X), (Option[U], Y)] = RightOuterBroadcastJoin.time {

    // key the RDDs and join
    RDDBoundGenericGenomicDataset[(Option[T], X), (Option[U], Y)](RightOuterTreeRegionJoin[T, X]().broadcastAndJoin(
      buildTree(flattenRddByRegions().map(f => (f._1.pad(flankSize), f._2))),
      genomicRdd.flattenRddByRegions()),
      sequences ++ genomicRdd.sequences,
      GenericConverter[(Option[T], X), (Option[U], Y)](kv => {
        // pad by -1 * flankSize to undo pad from preprocessing
        Seq(kv._1.map(v => getReferenceRegions(v)
          .map(_.pad(-1 * flankSize)))).flatten.flatten ++
          genomicRdd.getReferenceRegions(kv._2)
      },
        kv => (kv._1.map(productFn), genomicRdd.productFn(kv._2)),
        kv => (kv._1.map(unproductFn), genomicRdd.unproductFn(kv._2))),
      TagHolder[(Option[T], X), (Option[U], Y)]())
  }

  /**
   * Performs a broadcast right outer join between this RDD and data that has been broadcast.
   *
   * In a broadcast join, the left side of the join (broadcastTree) is broadcast to
   * to all the nodes in the cluster. The key equality
   * function used for this join is the reference region overlap function. Since this
   * is a right outer join, all values in the left table that do not overlap a
   * value from the right RDD are dropped. If a value from the right RDD does
   * not overlap any values in the left table, it will be paired with a `None`
   * in the product of the join. As compared to broadcastRegionJoin, this function allows the
   * broadcast object to be reused across multiple joins.
   *
   * @note This function differs from other region joins as it treats the calling RDD
   *   as the right side of the join, and not the left.
   *
   * @param broadcastTree The data on the left side of the join.
   * @return Returns a new genomic RDD containing all pairs of keys that
   *   overlapped in the genomic coordinate space.
   *
   * @see rightOuterBroadcastRegionJoin
   */
  def rightOuterBroadcastRegionJoinAgainst[X, Y <: Product, Z <: GenomicDataset[X, Y, Z]](
    broadcast: GenomicBroadcast[X, Y, Z])(
      implicit tTag: ClassTag[T], xTag: ClassTag[X],
      oyuTag: TypeTag[(Option[Y], U)]): GenericGenomicDataset[(Option[X], T), (Option[Y], U)] = RightOuterBroadcastJoin.time {

    // key the RDDs and join
    RDDBoundGenericGenomicDataset[(Option[X], T), (Option[Y], U)](RightOuterTreeRegionJoin[X, T]().join(
      broadcast.broadcastTree,
      flattenRddByRegions()),
      sequences ++ broadcast.backingDataset.sequences,
      GenericConverter[(Option[X], T), (Option[Y], U)](kv => {
        Seq(kv._1.map(v => broadcast.backingDataset.getReferenceRegions(v))).flatten.flatten ++
          getReferenceRegions(kv._2)
      },
        kv => (kv._1.map(broadcast.backingDataset.productFn), productFn(kv._2)),
        kv => (kv._1.map(broadcast.backingDataset.unproductFn), unproductFn(kv._2))),
      TagHolder[(Option[X], T), (Option[Y], U)]())
  }

  /**
   * Performs a broadcast right outer join between this RDD and another RDD.
   *
   * In a broadcast join, the left RDD (this RDD) is collected to the driver,
   * and broadcast to all the nodes in the cluster. The key equality function
   * used for this join is the reference region overlap function. Since this
   * is a right outer join, all values in the left RDD that do not overlap a
   * value from the right RDD are dropped. If a value from the right RDD does
   * not overlap any values in the left RDD, it will be paired with a `None`
   * in the product of the join.
   *
   * @param genomicRdd The right RDD in the join.
   * @return Returns a new genomic RDD containing all pairs of keys that
   *   overlapped in the genomic coordinate space, and all keys from the
   *   right RDD that did not overlap a key in the left RDD.
   *
   * @see rightOuterBroadcastRegionJoin
   */
  def rightOuterBroadcastRegionJoin[X, Y <: Product, Z <: GenomicDataset[X, Y, Z]](
    genomicRdd: GenomicDataset[X, Y, Z])(
      implicit tTag: ClassTag[T],
      xTag: ClassTag[X],
      otxTag: ClassTag[(Option[T], X)],
      ouyTag: TypeTag[(Option[U], Y)]): GenericGenomicDataset[(Option[T], X), (Option[U], Y)] = {

    rightOuterBroadcastRegionJoin(genomicRdd, 0L)
  }

  /**
   * Performs a broadcast inner join between this RDD and another RDD.
   *
   * In a broadcast join, the left RDD (this RDD) is collected to the driver,
   * and broadcast to all the nodes in the cluster. The key equality function
   * used for this join is the reference region overlap function. Since this
   * is an inner join, all values who do not overlap a value from the other
   * RDD are dropped. SparkR friendly variant.
   *
   * @param genomicRdd The right RDD in the join.
   * @param flankSize Sets a flankSize for the distance between elements to be
   *   joined. If set to 0, an overlap is required to join two elements.
   * @return Returns a new genomic RDD containing all pairs of keys that
   *   overlapped in the genomic coordinate space.
   *
   * @see broadcastRegionJoinAgainstAndGroupByRight
   */
  def broadcastRegionJoinAndGroupByRight[X, Y <: Product, Z <: GenomicDataset[X, Y, Z]](
    genomicRdd: GenomicDataset[X, Y, Z],
    flankSize: java.lang.Double): GenericGenomicDataset[(Iterable[T], X), (Seq[U], Y)] = {

    broadcastRegionJoinAndGroupByRight(genomicRdd, flankSize.toInt: java.lang.Integer)
  }

  /**
   * Performs a broadcast inner join between this RDD and another RDD.
   *
   * In a broadcast join, the left RDD (this RDD) is collected to the driver,
   * and broadcast to all the nodes in the cluster. The key equality function
   * used for this join is the reference region overlap function. Since this
   * is an inner join, all values who do not overlap a value from the other
   * RDD are dropped. PySpark/Java friendly variant.
   *
   * @param genomicRdd The right RDD in the join.
   * @param flankSize Sets a flankSize for the distance between elements to be
   *   joined. If set to 0, an overlap is required to join two elements.
   * @return Returns a new genomic RDD containing all pairs of keys that
   *   overlapped in the genomic coordinate space.
   *
   * @see broadcastRegionJoinAgainstAndGroupByRight
   */
  def broadcastRegionJoinAndGroupByRight[X, Y <: Product, Z <: GenomicDataset[X, Y, Z]](
    genomicRdd: GenomicDataset[X, Y, Z],
    flankSize: java.lang.Integer): GenericGenomicDataset[(Iterable[T], X), (Seq[U], Y)] = {

    implicit val tTag = ClassTag.AnyRef.asInstanceOf[ClassTag[T]]
    implicit val xTag = ClassTag.AnyRef.asInstanceOf[ClassTag[X]]
    implicit val txTag = ClassTag.AnyRef.asInstanceOf[ClassTag[(Iterable[T], X)]]
    implicit val u1Tag: TypeTag[U] = uTag
    implicit val u2Tag: TypeTag[Y] = genomicRdd.uTag
    implicit val uyTag = typeTag[(Seq[U], Y)]

    broadcastRegionJoinAndGroupByRight(genomicRdd, flankSize.toLong)
  }

  /**
   * Performs a broadcast inner join between this RDD and another RDD.
   *
   * In a broadcast join, the left RDD (this RDD) is collected to the driver,
   * and broadcast to all the nodes in the cluster. The key equality function
   * used for this join is the reference region overlap function. Since this
   * is an inner join, all values who do not overlap a value from the other
   * RDD are dropped.
   *
   * @param genomicRdd The right RDD in the join.
   * @param flankSize Sets a flankSize for the distance between elements to be
   *   joined. If set to 0, an overlap is required to join two elements.
   * @return Returns a new genomic RDD containing all pairs of keys that
   *   overlapped in the genomic coordinate space.
   *
   * @see broadcastRegionJoinAgainstAndGroupByRight
   */
  def broadcastRegionJoinAndGroupByRight[X, Y <: Product, Z <: GenomicDataset[X, Y, Z]](
    genomicRdd: GenomicDataset[X, Y, Z],
    flankSize: Long)(
      implicit tTag: ClassTag[T],
      xTag: ClassTag[X],
      itxTag: ClassTag[(Iterable[T], X)],
      iuyTag: TypeTag[(Seq[U], Y)]): GenericGenomicDataset[(Iterable[T], X), (Seq[U], Y)] = BroadcastJoinAndGroupByRight.time {

    // key the RDDs and join
    RDDBoundGenericGenomicDataset[(Iterable[T], X), (Seq[U], Y)](InnerTreeRegionJoinAndGroupByRight[T, X]().broadcastAndJoin(
      buildTree(flattenRddByRegions().map(f => (f._1.pad(flankSize), f._2))),
      genomicRdd.flattenRddByRegions()),
      sequences ++ genomicRdd.sequences,
      GenericConverter[(Iterable[T], X), (Seq[U], Y)](kv => {
        // pad by -1 * flankSize to undo pad from preprocessing
        (kv._1.flatMap(getReferenceRegions) ++
          genomicRdd.getReferenceRegions(kv._2))
          .toSeq
      },
        kv => (kv._1.map(productFn).toSeq, genomicRdd.productFn(kv._2)),
        kv => (kv._1.map(unproductFn), genomicRdd.unproductFn(kv._2))),
      TagHolder[(Iterable[T], X), (Seq[U], Y)]())
  }

  /**
   * Performs a broadcast inner join between this RDD and another RDD.
   *
   * In a broadcast join, the left side of the join (broadcastTree) is broadcast to
   * to all the nodes in the cluster. The key equality function
   * used for this join is the reference region overlap function. Since this
   * is an inner join, all values who do not overlap a value from the other
   * RDD are dropped. As compared to broadcastRegionJoin, this function allows
   * the broadcast object to be reused across multiple joins.
   *
   * @note This function differs from other region joins as it treats the calling RDD
   *   as the right side of the join, and not the left.
   *
   * @param broadcastTree The data on the left side of the join.
   * @return Returns a new genomic RDD containing all pairs of keys that
   *   overlapped in the genomic coordinate space.
   *
   * @see broadcastRegionJoinAndGroupByRight
   */
  def broadcastRegionJoinAgainstAndGroupByRight[X, Y <: Product, Z <: GenomicDataset[X, Y, Z]](
    broadcast: GenomicBroadcast[X, Y, Z])(
      implicit tTag: ClassTag[T], xTag: ClassTag[X],
      syuTag: TypeTag[(Seq[Y], U)]): GenericGenomicDataset[(Iterable[X], T), (Seq[Y], U)] = BroadcastJoinAndGroupByRight.time {

    // key the RDDs and join
    RDDBoundGenericGenomicDataset[(Iterable[X], T), (Seq[Y], U)](InnerTreeRegionJoinAndGroupByRight[X, T]().join(
      broadcast.broadcastTree,
      flattenRddByRegions()),
      sequences ++ broadcast.backingDataset.sequences,
      GenericConverter[(Iterable[X], T), (Seq[Y], U)](kv => {
        // pad by -1 * flankSize to undo pad from preprocessing
        (kv._1.flatMap(broadcast.backingDataset.getReferenceRegions) ++
          getReferenceRegions(kv._2))
          .toSeq
      },
        kv => (kv._1.map(broadcast.backingDataset.productFn).toSeq, productFn(kv._2)),
        kv => (kv._1.map(broadcast.backingDataset.unproductFn), unproductFn(kv._2))),
      TagHolder[(Iterable[X], T), (Seq[Y], U)]())
  }

  /**
   * Performs a broadcast inner join between this RDD and another RDD.
   *
   * In a broadcast join, the left RDD (this RDD) is collected to the driver,
   * and broadcast to all the nodes in the cluster. The key equality function
   * used for this join is the reference region overlap function. Since this
   * is an inner join, all values who do not overlap a value from the other
   * RDD are dropped.
   *
   * @param genomicRdd The right RDD in the join.
   * @return Returns a new genomic RDD containing all pairs of keys that
   *   overlapped in the genomic coordinate space.
   *
   * @see broadcastRegionJoinAgainstAndGroupByRight
   */
  def broadcastRegionJoinAndGroupByRight[X, Y <: Product, Z <: GenomicDataset[X, Y, Z]](
    genomicRdd: GenomicDataset[X, Y, Z])(
      implicit tTag: ClassTag[T],
      xTag: ClassTag[X],
      itxTag: ClassTag[(Iterable[T], X)],
      iuyTag: TypeTag[(Seq[U], Y)]): GenericGenomicDataset[(Iterable[T], X), (Seq[U], Y)] = {

    broadcastRegionJoinAndGroupByRight(genomicRdd, 0L)
  }

  /**
   * Performs a broadcast right outer join between this RDD and another RDD.
   *
   * In a broadcast join, the left side of the join (broadcastTree) is broadcast to
   * to all the nodes in the cluster. The key equality function
   * used for this join is the reference region overlap function. Since this
   * is a right outer join, all values in the left RDD that do not overlap a
   * value from the right RDD are dropped. If a value from the right RDD does
   * not overlap any values in the left RDD, it will be paired with a `None`
   * in the product of the join. SparkR friendly variant.
   *
   * @param genomicRdd The right RDD in the join.
   * @param flankSize Sets a flankSize for the distance between elements to be
   *   joined. If set to 0, an overlap is required to join two elements.
   * @return Returns a new genomic RDD containing all pairs of keys that
   *   overlapped in the genomic coordinate space, and all keys from the
   *   right RDD that did not overlap a key in the left RDD.
   *
   * @see rightOuterBroadcastRegionJoinAgainstAndGroupByRight
   */
  def rightOuterBroadcastRegionJoinAndGroupByRight[X, Y <: Product, Z <: GenomicDataset[X, Y, Z]](
    genomicRdd: GenomicDataset[X, Y, Z],
    flankSize: java.lang.Double): GenericGenomicDataset[(Iterable[T], X), (Seq[U], Y)] = {

    rightOuterBroadcastRegionJoinAndGroupByRight(genomicRdd, flankSize.toInt: java.lang.Integer)
  }

  /**
   * Performs a broadcast right outer join between this RDD and another RDD.
   *
   * In a broadcast join, the left side of the join (broadcastTree) is broadcast to
   * to all the nodes in the cluster. The key equality function
   * used for this join is the reference region overlap function. Since this
   * is a right outer join, all values in the left RDD that do not overlap a
   * value from the right RDD are dropped. If a value from the right RDD does
   * not overlap any values in the left RDD, it will be paired with a `None`
   * in the product of the join. PySpark/Java friendly variant.
   *
   * @param genomicRdd The right RDD in the join.
   * @param flankSize Sets a flankSize for the distance between elements to be
   *   joined. If set to 0, an overlap is required to join two elements.
   * @return Returns a new genomic RDD containing all pairs of keys that
   *   overlapped in the genomic coordinate space, and all keys from the
   *   right RDD that did not overlap a key in the left RDD.
   *
   * @see rightOuterBroadcastRegionJoinAgainstAndGroupByRight
   */
  def rightOuterBroadcastRegionJoinAndGroupByRight[X, Y <: Product, Z <: GenomicDataset[X, Y, Z]](
    genomicRdd: GenomicDataset[X, Y, Z],
    flankSize: java.lang.Integer): GenericGenomicDataset[(Iterable[T], X), (Seq[U], Y)] = {

    implicit val tTag = ClassTag.AnyRef.asInstanceOf[ClassTag[T]]
    implicit val xTag = ClassTag.AnyRef.asInstanceOf[ClassTag[X]]
    implicit val txTag = ClassTag.AnyRef.asInstanceOf[ClassTag[(Iterable[T], X)]]
    implicit val u1Tag: TypeTag[U] = uTag
    implicit val u2Tag: TypeTag[Y] = genomicRdd.uTag
    implicit val uyTag = typeTag[(Seq[U], Y)]

    rightOuterBroadcastRegionJoinAndGroupByRight(genomicRdd, flankSize.toLong)
  }

  /**
   * Performs a broadcast right outer join between this RDD and another RDD.
   *
   * In a broadcast join, the left side of the join (broadcastTree) is broadcast to
   * to all the nodes in the cluster. The key equality function
   * used for this join is the reference region overlap function. Since this
   * is a right outer join, all values in the left RDD that do not overlap a
   * value from the right RDD are dropped. If a value from the right RDD does
   * not overlap any values in the left RDD, it will be paired with a `None`
   * in the product of the join.
   *
   * @param genomicRdd The right RDD in the join.
   * @param flankSize Sets a flankSize for the distance between elements to be
   *   joined. If set to 0, an overlap is required to join two elements.
   * @return Returns a new genomic RDD containing all pairs of keys that
   *   overlapped in the genomic coordinate space, and all keys from the
   *   right RDD that did not overlap a key in the left RDD.
   *
   * @see rightOuterBroadcastRegionJoinAgainstAndGroupByRight
   */
  def rightOuterBroadcastRegionJoinAndGroupByRight[X, Y <: Product, Z <: GenomicDataset[X, Y, Z]](
    genomicRdd: GenomicDataset[X, Y, Z],
    flankSize: Long)(
      implicit tTag: ClassTag[T],
      xTag: ClassTag[X],
      itxTag: ClassTag[(Iterable[T], X)],
      iuyTag: TypeTag[(Seq[U], Y)]): GenericGenomicDataset[(Iterable[T], X), (Seq[U], Y)] = RightOuterBroadcastJoinAndGroupByRight.time {

    // key the RDDs and join
    RDDBoundGenericGenomicDataset[(Iterable[T], X), (Seq[U], Y)](RightOuterTreeRegionJoinAndGroupByRight[T, X]().broadcastAndJoin(
      buildTree(flattenRddByRegions().map(f => (f._1.pad(flankSize), f._2))),
      genomicRdd.flattenRddByRegions()),
      sequences ++ genomicRdd.sequences,
      GenericConverter[(Iterable[T], X), (Seq[U], Y)](kv => {
        // pad by -1 * flankSize to undo pad from preprocessing
        Seq(kv._1.map(v => getReferenceRegions(v)
          .map(_.pad(-1 * flankSize)))).flatten.flatten ++
          genomicRdd.getReferenceRegions(kv._2)
      },
        kv => (kv._1.map(productFn).toSeq, genomicRdd.productFn(kv._2)),
        kv => (kv._1.map(unproductFn), genomicRdd.unproductFn(kv._2))),
      TagHolder[(Iterable[T], X), (Seq[U], Y)]())
  }

  /**
   * Performs a broadcast right outer join between this RDD and another RDD.
   *
   * In a broadcast join, the left side of the join (broadcastTree) is broadcast to
   * to all the nodes in the cluster. The key equality function
   * used for this join is the reference region overlap function. Since this
   * is a right outer join, all values in the left table that do not overlap a
   * value from the right RDD are dropped. If a value from the right RDD does
   * not overlap any values in the left table, it will be paired with a `None`
   * in the product of the join. As compared to broadcastRegionJoin, this
   * function allows the broadcast object to be reused across multiple joins.
   *
   * @note This function differs from other region joins as it treats the calling RDD
   *   as the right side of the join, and not the left.
   *
   * @param broadcastTree The data on the left side of the join.
   * @return Returns a new genomic RDD containing all pairs of keys that
   *   overlapped in the genomic coordinate space.
   *
   * @see rightOuterBroadcastRegionJoinAndGroupByRight
   */
  def rightOuterBroadcastRegionJoinAgainstAndGroupByRight[X, Y <: Product, Z <: GenomicDataset[X, Y, Z]](
    broadcast: GenomicBroadcast[X, Y, Z])(
      implicit tTag: ClassTag[T], xTag: ClassTag[X],
      syuTag: TypeTag[(Seq[Y], U)]): GenericGenomicDataset[(Iterable[X], T), (Seq[Y], U)] = RightOuterBroadcastJoinAndGroupByRight.time {

    // key the RDDs and join
    RDDBoundGenericGenomicDataset[(Iterable[X], T), (Seq[Y], U)](RightOuterTreeRegionJoinAndGroupByRight[X, T]().join(
      broadcast.broadcastTree,
      flattenRddByRegions()),
      sequences ++ broadcast.backingDataset.sequences,
      GenericConverter[(Iterable[X], T), (Seq[Y], U)](kv => {
        // pad by -1 * flankSize to undo pad from preprocessing
        Seq(kv._1.map(v => broadcast.backingDataset.getReferenceRegions(v))).flatten.flatten ++
          getReferenceRegions(kv._2)
      },
        kv => (kv._1.map(broadcast.backingDataset.productFn).toSeq, productFn(kv._2)),
        kv => (kv._1.map(broadcast.backingDataset.unproductFn), unproductFn(kv._2))),
      TagHolder[(Iterable[X], T), (Seq[Y], U)]())
  }

  /**
   * Performs a broadcast right outer join between this RDD and another RDD.
   *
   * In a broadcast join, the left side of the join (broadcastTree) is broadcast to
   * to all the nodes in the cluster. The key equality function
   * used for this join is the reference region overlap function. Since this
   * is a right outer join, all values in the left RDD that do not overlap a
   * value from the right RDD are dropped. If a value from the right RDD does
   * not overlap any values in the left RDD, it will be paired with a `None`
   * in the product of the join.
   *
   * @param genomicRdd The right RDD in the join.
   * @return Returns a new genomic RDD containing all pairs of keys that
   *   overlapped in the genomic coordinate space, and all keys from the
   *   right RDD that did not overlap a key in the left RDD.
   *
   * @see rightOuterBroadcastRegionJoinAgainstAndGroupByRight
   */
  def rightOuterBroadcastRegionJoinAndGroupByRight[X, Y <: Product, Z <: GenomicDataset[X, Y, Z]](
    genomicRdd: GenomicDataset[X, Y, Z])(
      implicit tTag: ClassTag[T],
      xTag: ClassTag[X],
      itxTag: ClassTag[(Iterable[T], X)],
      iuyTag: TypeTag[(Seq[U], Y)]): GenericGenomicDataset[(Iterable[T], X), (Seq[U], Y)] = {

    rightOuterBroadcastRegionJoinAndGroupByRight(genomicRdd, 0L)
  }

  /**
   * Prepares two RDDs to be joined with any shuffleRegionJoin. This includes copartition
   * and sort of the rightRdd if necessary.
   *
   * @param genomicRdd The RDD to join to.
   * @param optPartitions Optionally sets the number of output partitions. If
   *   None, the number of partitions on the resulting RDD does not change.
   * @param flankSize Sets a flankSize for the distance between elements to be
   *   joined. If set to 0, an overlap is required to join two elements.
   * @return a case class containing all the prepared data for ShuffleRegionJoins
   */
  private def prepareForShuffleRegionJoin[X, Y <: Product, Z <: GenomicDataset[X, Y, Z]](
    genomicRdd: GenomicDataset[X, Y, Z],
    optPartitions: Option[Int] = None,
    flankSize: Long)(
      implicit tTag: ClassTag[T], xTag: ClassTag[X]): (RDD[(ReferenceRegion, T)], RDD[(ReferenceRegion, X)]) = {

    val partitions = optPartitions.getOrElse(this.rdd.partitions.length)

    val (leftRdd, rightRdd) = (isSorted, genomicRdd.isSorted) match {
      case (true, _)     => (this, genomicRdd.copartitionByReferenceRegion(this, flankSize))
      case (false, true) => (copartitionByReferenceRegion(genomicRdd, flankSize), genomicRdd)
      case (false, false) => {
        val repartitionedRdd =
          sortLexicographically(storePartitionMap = true, partitions = partitions)

        (repartitionedRdd, genomicRdd.copartitionByReferenceRegion(repartitionedRdd, flankSize))
      }
    }
    (leftRdd.flattenRddByRegions().map(f => (f._1.pad(flankSize), f._2)),
      rightRdd.flattenRddByRegions())
  }

  /**
   * Performs a sort-merge inner join between this RDD and another RDD.
   *
   * In a sort-merge join, both RDDs are co-partitioned and sorted. The
   * partitions are then zipped, and we do a merge join on each partition.
   * The key equality function used for this join is the reference region
   * overlap function. Since this is an inner join, all values who do not
   * overlap a value from the other RDD are dropped. SparkR friendly variant.
   *
   * @param genomicRdd The right RDD in the join.
   * @param flankSize Sets a flankSize for the distance between elements to be
   *   joined. If set to 0, an overlap is required to join two elements.
   * @return Returns a new genomic RDD containing all pairs of keys that
   *   overlapped in the genomic coordinate space.
   */
  def shuffleRegionJoin[X, Y <: Product, Z <: GenomicDataset[X, Y, Z]](
    genomicRdd: GenomicDataset[X, Y, Z],
    flankSize: java.lang.Double): GenericGenomicDataset[(T, X), (U, Y)] = {

    shuffleRegionJoin(genomicRdd, flankSize.toInt: java.lang.Integer)
  }

  /**
   * Performs a sort-merge inner join between this RDD and another RDD.
   *
   * In a sort-merge join, both RDDs are co-partitioned and sorted. The
   * partitions are then zipped, and we do a merge join on each partition.
   * The key equality function used for this join is the reference region
   * overlap function. Since this is an inner join, all values who do not
   * overlap a value from the other RDD are dropped. PySpark/Java friendly
   * variant.
   *
   * @param genomicRdd The right RDD in the join.
   * @param flankSize Sets a flankSize for the distance between elements to be
   *   joined. If set to 0, an overlap is required to join two elements.
   * @return Returns a new genomic RDD containing all pairs of keys that
   *   overlapped in the genomic coordinate space.
   */
  def shuffleRegionJoin[X, Y <: Product, Z <: GenomicDataset[X, Y, Z]](
    genomicRdd: GenomicDataset[X, Y, Z],
    flankSize: java.lang.Integer): GenericGenomicDataset[(T, X), (U, Y)] = {

    implicit val tTag = ClassTag.AnyRef.asInstanceOf[ClassTag[T]]
    implicit val xTag = ClassTag.AnyRef.asInstanceOf[ClassTag[X]]
    implicit val txTag = ClassTag.AnyRef.asInstanceOf[ClassTag[(T, X)]]
    implicit val u1Tag: TypeTag[U] = uTag
    implicit val u2Tag: TypeTag[Y] = genomicRdd.uTag
    implicit val uyTag = typeTag[(U, Y)]

    shuffleRegionJoin(genomicRdd, flankSize.toLong)
  }

  /**
   * Performs a sort-merge inner join between this RDD and another RDD.
   *
   * In a sort-merge join, both RDDs are co-partitioned and sorted. The
   * partitions are then zipped, and we do a merge join on each partition.
   * The key equality function used for this join is the reference region
   * overlap function. Since this is an inner join, all values who do not
   * overlap a value from the other RDD are dropped.
   *
   * @param genomicRdd The right RDD in the join.
   * @param optPartitions Optionally sets the number of output partitions. If
   *   None, the number of partitions on the resulting RDD does not change.
   * @param flankSize Sets a flankSize for the distance between elements to be
   *   joined. If set to 0, an overlap is required to join two elements.
   * @return Returns a new genomic RDD containing all pairs of keys that
   *   overlapped in the genomic coordinate space.
   */
  private[rdd] def shuffleRegionJoin[X, Y <: Product, Z <: GenomicDataset[X, Y, Z]](
    genomicRdd: GenomicDataset[X, Y, Z],
    optPartitions: Option[Int],
    flankSize: Long)(
      implicit tTag: ClassTag[T],
      xTag: ClassTag[X],
      txTag: ClassTag[(T, X)],
      uyTag: TypeTag[(U, Y)]): GenericGenomicDataset[(T, X), (U, Y)] = InnerShuffleJoin.time {

    val (leftRddToJoin, rightRddToJoin) =
      prepareForShuffleRegionJoin(genomicRdd, optPartitions, flankSize)

    // what sequences do we wind up with at the end?
    val combinedSequences = sequences ++ genomicRdd.sequences

    RDDBoundGenericGenomicDataset[(T, X), (U, Y)](
      InnerShuffleRegionJoin[T, X](leftRddToJoin, rightRddToJoin)
        .compute(),
      combinedSequences,
      GenericConverter[(T, X), (U, Y)](kv => {
        // pad by -1 * flankSize to undo pad from preprocessing
        getReferenceRegions(kv._1).map(_.pad(-1 * flankSize)) ++
          genomicRdd.getReferenceRegions(kv._2)
      },
        kv => (productFn(kv._1), genomicRdd.productFn(kv._2)),
        kv => (unproductFn(kv._1), genomicRdd.unproductFn(kv._2))),
      TagHolder[(T, X), (U, Y)]())
  }

  /**
   * Performs a sort-merge inner join between this RDD and another RDD.
   *
   * In a sort-merge join, both RDDs are co-partitioned and sorted. The
   * partitions are then zipped, and we do a merge join on each partition.
   * The key equality function used for this join is the reference region
   * overlap function. Since this is an inner join, all values who do not
   * overlap a value from the other RDD are dropped.
   *
   * @param genomicRdd The right RDD in the join.
   * @param flankSize Sets a flankSize for the distance between elements to be
   *   joined. If set to 0, an overlap is required to join two elements.
   * @return Returns a new genomic RDD containing all pairs of keys that
   *   overlapped in the genomic coordinate space.
   */
  def shuffleRegionJoin[X, Y <: Product, Z <: GenomicDataset[X, Y, Z]](
    genomicRdd: GenomicDataset[X, Y, Z],
    flankSize: Long)(
      implicit tTag: ClassTag[T],
      xTag: ClassTag[X],
      txTag: ClassTag[(T, X)],
      uyTag: TypeTag[(U, Y)]): GenericGenomicDataset[(T, X), (U, Y)] = {

    shuffleRegionJoin(genomicRdd, None, flankSize)
  }

  /**
   * Performs a sort-merge inner join between this RDD and another RDD.
   *
   * In a sort-merge join, both RDDs are co-partitioned and sorted. The
   * partitions are then zipped, and we do a merge join on each partition.
   * The key equality function used for this join is the reference region
   * overlap function. Since this is an inner join, all values who do not
   * overlap a value from the other RDD are dropped.
   *
   * @param genomicRdd The right RDD in the join.
   * @return Returns a new genomic RDD containing all pairs of keys that
   *   overlapped in the genomic coordinate space.
   */
  def shuffleRegionJoin[X, Y <: Product, Z <: GenomicDataset[X, Y, Z]](
    genomicRdd: GenomicDataset[X, Y, Z])(
      implicit tTag: ClassTag[T],
      xTag: ClassTag[X],
      txTag: ClassTag[(T, X)],
      uyTag: TypeTag[(U, Y)]): GenericGenomicDataset[(T, X), (U, Y)] = {

    shuffleRegionJoin(genomicRdd, None, 0L)
  }

  /**
   * Performs a sort-merge right outer join between this RDD and another RDD.
   *
   * In a sort-merge join, both RDDs are co-partitioned and sorted. The
   * partitions are then zipped, and we do a merge join on each partition.
   * The key equality function used for this join is the reference region
   * overlap function. Since this is a right outer join, all values in the
   * left RDD that do not overlap a value from the right RDD are dropped.
   * If a value from the right RDD does not overlap any values in the left
   * RDD, it will be paired with a `None` in the product of the join. SparkR
   * friendly variant.
   *
   * @param genomicRdd The right RDD in the join.
   * @param flankSize Sets a flankSize for the distance between elements to be
   *   joined. If set to 0, an overlap is required to join two elements.
   * @return Returns a new genomic RDD containing all pairs of keys that
   *   overlapped in the genomic coordinate space, and all keys from the
   *   right RDD that did not overlap a key in the left RDD.
   */
  def rightOuterShuffleRegionJoin[X, Y <: Product, Z <: GenomicDataset[X, Y, Z]](
    genomicRdd: GenomicDataset[X, Y, Z],
    flankSize: java.lang.Double): GenericGenomicDataset[(Option[T], X), (Option[U], Y)] = {

    rightOuterShuffleRegionJoin(genomicRdd, flankSize.toInt: java.lang.Integer)
  }

  /**
   * Performs a sort-merge right outer join between this RDD and another RDD.
   *
   * In a sort-merge join, both RDDs are co-partitioned and sorted. The
   * partitions are then zipped, and we do a merge join on each partition.
   * The key equality function used for this join is the reference region
   * overlap function. Since this is a right outer join, all values in the
   * left RDD that do not overlap a value from the right RDD are dropped.
   * If a value from the right RDD does not overlap any values in the left
   * RDD, it will be paired with a `None` in the product of the join.
   * PySpark/Java friendly variant.
   *
   * @param genomicRdd The right RDD in the join.
   * @param flankSize Sets a flankSize for the distance between elements to be
   *   joined. If set to 0, an overlap is required to join two elements.
   * @return Returns a new genomic RDD containing all pairs of keys that
   *   overlapped in the genomic coordinate space, and all keys from the
   *   right RDD that did not overlap a key in the left RDD.
   */
  def rightOuterShuffleRegionJoin[X, Y <: Product, Z <: GenomicDataset[X, Y, Z]](
    genomicRdd: GenomicDataset[X, Y, Z],
    flankSize: java.lang.Integer): GenericGenomicDataset[(Option[T], X), (Option[U], Y)] = {

    implicit val tTag = ClassTag.AnyRef.asInstanceOf[ClassTag[T]]
    implicit val xTag = ClassTag.AnyRef.asInstanceOf[ClassTag[X]]
    implicit val txTag = ClassTag.AnyRef.asInstanceOf[ClassTag[(Option[T], X)]]
    implicit val u1Tag: TypeTag[U] = uTag
    implicit val u2Tag: TypeTag[Y] = genomicRdd.uTag
    implicit val uyTag = typeTag[(Option[U], Y)]

    rightOuterShuffleRegionJoin(genomicRdd, flankSize.toLong)
  }

  /**
   * Performs a sort-merge right outer join between this RDD and another RDD.
   *
   * In a sort-merge join, both RDDs are co-partitioned and sorted. The
   * partitions are then zipped, and we do a merge join on each partition.
   * The key equality function used for this join is the reference region
   * overlap function. Since this is a right outer join, all values in the
   * left RDD that do not overlap a value from the right RDD are dropped.
   * If a value from the right RDD does not overlap any values in the left
   * RDD, it will be paired with a `None` in the product of the join.
   *
   * @param genomicRdd The right RDD in the join.
   * @param optPartitions Optionally sets the number of output partitions. If
   *   None, the number of partitions on the resulting RDD does not change.
   * @param flankSize Sets a flankSize for the distance between elements to be
   *   joined. If set to 0, an overlap is required to join two elements.
   * @return Returns a new genomic RDD containing all pairs of keys that
   *   overlapped in the genomic coordinate space, and all keys from the
   *   right RDD that did not overlap a key in the left RDD.
   */
  private[rdd] def rightOuterShuffleRegionJoin[X, Y <: Product, Z <: GenomicDataset[X, Y, Z]](
    genomicRdd: GenomicDataset[X, Y, Z],
    optPartitions: Option[Int],
    flankSize: Long)(
      implicit tTag: ClassTag[T],
      xTag: ClassTag[X],
      otxTag: ClassTag[(Option[T], X)],
      ouyTag: TypeTag[(Option[U], Y)]): GenericGenomicDataset[(Option[T], X), (Option[U], Y)] = RightOuterShuffleJoin.time {

    val (leftRddToJoin, rightRddToJoin) =
      prepareForShuffleRegionJoin(genomicRdd, optPartitions, flankSize)

    // what sequences do we wind up with at the end?
    val combinedSequences = sequences ++ genomicRdd.sequences

    RDDBoundGenericGenomicDataset[(Option[T], X), (Option[U], Y)](
      LeftOuterShuffleRegionJoin[X, T](rightRddToJoin, leftRddToJoin)
        .compute()
        .map(_.swap),
      combinedSequences,
      GenericConverter[(Option[T], X), (Option[U], Y)](kv => {
        // pad by -1 * flankSize to undo pad from preprocessing
        Seq(kv._1.map(v => getReferenceRegions(v)
          .map(_.pad(-1 * flankSize)))).flatten.flatten ++
          genomicRdd.getReferenceRegions(kv._2)
      },
        kv => (kv._1.map(productFn), genomicRdd.productFn(kv._2)),
        kv => (kv._1.map(unproductFn), genomicRdd.unproductFn(kv._2))),
      TagHolder[(Option[T], X), (Option[U], Y)]())
  }

  /**
   * Performs a sort-merge right outer join between this RDD and another RDD.
   *
   * In a sort-merge join, both RDDs are co-partitioned and sorted. The
   * partitions are then zipped, and we do a merge join on each partition.
   * The key equality function used for this join is the reference region
   * overlap function. Since this is a right outer join, all values in the
   * left RDD that do not overlap a value from the right RDD are dropped.
   * If a value from the right RDD does not overlap any values in the left
   * RDD, it will be paired with a `None` in the product of the join.
   *
   * @param genomicRdd The right RDD in the join.
   * @param flankSize Sets a flankSize for the distance between elements to be
   *   joined. If set to 0, an overlap is required to join two elements.
   * @return Returns a new genomic RDD containing all pairs of keys that
   *   overlapped in the genomic coordinate space, and all keys from the
   *   right RDD that did not overlap a key in the left RDD.
   */
  def rightOuterShuffleRegionJoin[X, Y <: Product, Z <: GenomicDataset[X, Y, Z]](
    genomicRdd: GenomicDataset[X, Y, Z],
    flankSize: Long)(
      implicit tTag: ClassTag[T],
      xTag: ClassTag[X],
      otxTag: ClassTag[(Option[T], X)],
      ouyTag: TypeTag[(Option[U], Y)]): GenericGenomicDataset[(Option[T], X), (Option[U], Y)] = {

    rightOuterShuffleRegionJoin(genomicRdd, None, flankSize)
  }

  /**
   * Performs a sort-merge right outer join between this RDD and another RDD.
   *
   * In a sort-merge join, both RDDs are co-partitioned and sorted. The
   * partitions are then zipped, and we do a merge join on each partition.
   * The key equality function used for this join is the reference region
   * overlap function. Since this is a right outer join, all values in the
   * left RDD that do not overlap a value from the right RDD are dropped.
   * If a value from the right RDD does not overlap any values in the left
   * RDD, it will be paired with a `None` in the product of the join.
   *
   * @param genomicRdd The right RDD in the join.
   * @return Returns a new genomic RDD containing all pairs of keys that
   *   overlapped in the genomic coordinate space, and all keys from the
   *   right RDD that did not overlap a key in the left RDD.
   */
  def rightOuterShuffleRegionJoin[X, Y <: Product, Z <: GenomicDataset[X, Y, Z]](
    genomicRdd: GenomicDataset[X, Y, Z])(
      implicit tTag: ClassTag[T],
      xTag: ClassTag[X],
      otxTag: ClassTag[(Option[T], X)],
      ouyTag: TypeTag[(Option[U], Y)]): GenericGenomicDataset[(Option[T], X), (Option[U], Y)] = {

    rightOuterShuffleRegionJoin(genomicRdd, None, 0L)
  }

  /**
   * Performs a sort-merge left outer join between this RDD and another RDD.
   *
   * In a sort-merge join, both RDDs are co-partitioned and sorted. The
   * partitions are then zipped, and we do a merge join on each partition.
   * The key equality function used for this join is the reference region
   * overlap function. Since this is a left outer join, all values in the
   * right RDD that do not overlap a value from the left RDD are dropped.
   * If a value from the left RDD does not overlap any values in the right
   * RDD, it will be paired with a `None` in the product of the join. SparkR
   * friendly variant.
   *
   * @param genomicRdd The right RDD in the join.
   * @param flankSize Sets a flankSize for the distance between elements to be
   *   joined. If set to 0, an overlap is required to join two elements.
   * @return Returns a new genomic RDD containing all pairs of keys that
   *   overlapped in the genomic coordinate space, and all keys from the
   *   left RDD that did not overlap a key in the right RDD.
   */
  def leftOuterShuffleRegionJoin[X, Y <: Product, Z <: GenomicDataset[X, Y, Z]](
    genomicRdd: GenomicDataset[X, Y, Z],
    flankSize: java.lang.Double): GenericGenomicDataset[(T, Option[X]), (U, Option[Y])] = {

    leftOuterShuffleRegionJoin(genomicRdd, flankSize.toInt: java.lang.Integer)
  }

  /**
   * Performs a sort-merge left outer join between this RDD and another RDD.
   *
   * In a sort-merge join, both RDDs are co-partitioned and sorted. The
   * partitions are then zipped, and we do a merge join on each partition.
   * The key equality function used for this join is the reference region
   * overlap function. Since this is a left outer join, all values in the
   * right RDD that do not overlap a value from the left RDD are dropped.
   * If a value from the left RDD does not overlap any values in the right
   * RDD, it will be paired with a `None` in the product of the join.
   * PySpark/Java friendly variant.
   *
   * @param genomicRdd The right RDD in the join.
   * @param flankSize Sets a flankSize for the distance between elements to be
   *   joined. If set to 0, an overlap is required to join two elements.
   * @return Returns a new genomic RDD containing all pairs of keys that
   *   overlapped in the genomic coordinate space, and all keys from the
   *   left RDD that did not overlap a key in the right RDD.
   */
  def leftOuterShuffleRegionJoin[X, Y <: Product, Z <: GenomicDataset[X, Y, Z]](
    genomicRdd: GenomicDataset[X, Y, Z],
    flankSize: java.lang.Integer): GenericGenomicDataset[(T, Option[X]), (U, Option[Y])] = {

    implicit val tTag = ClassTag.AnyRef.asInstanceOf[ClassTag[T]]
    implicit val xTag = ClassTag.AnyRef.asInstanceOf[ClassTag[X]]
    implicit val txTag = ClassTag.AnyRef.asInstanceOf[ClassTag[(T, Option[X])]]
    implicit val u1Tag: TypeTag[U] = uTag
    implicit val u2Tag: TypeTag[Y] = genomicRdd.uTag
    implicit val uyTag = typeTag[(U, Option[Y])]

    leftOuterShuffleRegionJoin(genomicRdd, flankSize.toLong)
  }

  /**
   * Performs a sort-merge left outer join between this RDD and another RDD.
   *
   * In a sort-merge join, both RDDs are co-partitioned and sorted. The
   * partitions are then zipped, and we do a merge join on each partition.
   * The key equality function used for this join is the reference region
   * overlap function. Since this is a left outer join, all values in the
   * right RDD that do not overlap a value from the left RDD are dropped.
   * If a value from the left RDD does not overlap any values in the right
   * RDD, it will be paired with a `None` in the product of the join.
   *
   * @param genomicRdd The right RDD in the join.
   * @param optPartitions Optionally sets the number of output partitions. If
   *   None, the number of partitions on the resulting RDD does not change.
   * @param flankSize Sets a flankSize for the distance between elements to be
   *   joined. If set to 0, an overlap is required to join two elements.
   * @return Returns a new genomic RDD containing all pairs of keys that
   *   overlapped in the genomic coordinate space, and all keys from the
   *   left RDD that did not overlap a key in the right RDD.
   */
  private[rdd] def leftOuterShuffleRegionJoin[X, Y <: Product, Z <: GenomicDataset[X, Y, Z]](
    genomicRdd: GenomicDataset[X, Y, Z],
    optPartitions: Option[Int],
    flankSize: Long)(
      implicit tTag: ClassTag[T],
      xTag: ClassTag[X],
      toxTag: ClassTag[(T, Option[X])],
      uoyTag: TypeTag[(U, Option[Y])]): GenericGenomicDataset[(T, Option[X]), (U, Option[Y])] = LeftOuterShuffleJoin.time {

    val (leftRddToJoin, rightRddToJoin) =
      prepareForShuffleRegionJoin(genomicRdd, optPartitions, flankSize)

    // what sequences do we wind up with at the end?
    val combinedSequences = sequences ++ genomicRdd.sequences

    RDDBoundGenericGenomicDataset[(T, Option[X]), (U, Option[Y])](
      LeftOuterShuffleRegionJoin[T, X](leftRddToJoin, rightRddToJoin)
        .compute(),
      combinedSequences,
      GenericConverter[(T, Option[X]), (U, Option[Y])](kv => {
        // pad by -1 * flankSize to undo pad from preprocessing
        getReferenceRegions(kv._1).map(_.pad(-1 * flankSize)) ++
          Seq(kv._2.map(v => genomicRdd.getReferenceRegions(v))).flatten.flatten
      },
        kv => (productFn(kv._1), kv._2.map(genomicRdd.productFn)),
        kv => (unproductFn(kv._1), kv._2.map(genomicRdd.unproductFn))),
      TagHolder[(T, Option[X]), (U, Option[Y])]())
  }

  /**
   * Performs a sort-merge left outer join between this RDD and another RDD.
   *
   * In a sort-merge join, both RDDs are co-partitioned and sorted. The
   * partitions are then zipped, and we do a merge join on each partition.
   * The key equality function used for this join is the reference region
   * overlap function. Since this is a left outer join, all values in the
   * right RDD that do not overlap a value from the left RDD are dropped.
   * If a value from the left RDD does not overlap any values in the right
   * RDD, it will be paired with a `None` in the product of the join.
   *
   * @param genomicRdd The right RDD in the join.
   * @param flankSize Sets a flankSize for the distance between elements to be
   *   joined. If set to 0, an overlap is required to join two elements.
   * @return Returns a new genomic RDD containing all pairs of keys that
   *   overlapped in the genomic coordinate space, and all keys from the
   *   left RDD that did not overlap a key in the right RDD.
   */
  def leftOuterShuffleRegionJoin[X, Y <: Product, Z <: GenomicDataset[X, Y, Z]](
    genomicRdd: GenomicDataset[X, Y, Z],
    flankSize: Long)(
      implicit tTag: ClassTag[T],
      xTag: ClassTag[X],
      toxTag: ClassTag[(T, Option[X])],
      uoyTag: TypeTag[(U, Option[Y])]): GenericGenomicDataset[(T, Option[X]), (U, Option[Y])] = {

    leftOuterShuffleRegionJoin(genomicRdd, None, flankSize)
  }

  /**
   * Performs a sort-merge left outer join between this RDD and another RDD.
   *
   * In a sort-merge join, both RDDs are co-partitioned and sorted. The
   * partitions are then zipped, and we do a merge join on each partition.
   * The key equality function used for this join is the reference region
   * overlap function. Since this is a left outer join, all values in the
   * right RDD that do not overlap a value from the left RDD are dropped.
   * If a value from the left RDD does not overlap any values in the right
   * RDD, it will be paired with a `None` in the product of the join.
   *
   * @param genomicRdd The right RDD in the join.
   * @return Returns a new genomic RDD containing all pairs of keys that
   *   overlapped in the genomic coordinate space, and all keys from the
   *   left RDD that did not overlap a key in the right RDD.
   */
  def leftOuterShuffleRegionJoin[X, Y <: Product, Z <: GenomicDataset[X, Y, Z]](
    genomicRdd: GenomicDataset[X, Y, Z])(
      implicit tTag: ClassTag[T],
      xTag: ClassTag[X],
      toxTag: ClassTag[(T, Option[X])],
      uoyTag: TypeTag[(U, Option[Y])]): GenericGenomicDataset[(T, Option[X]), (U, Option[Y])] = {

    leftOuterShuffleRegionJoin(genomicRdd, None, 0L)
  }

  /**
   * Performs a sort-merge left outer join between this RDD and another RDD,
   * followed by a groupBy on the left value.
   *
   * In a sort-merge join, both RDDs are co-partitioned and sorted. The
   * partitions are then zipped, and we do a merge join on each partition.
   * The key equality function used for this join is the reference region
   * overlap function. Since this is a left outer join, all values in the
   * right RDD that do not overlap a value from the left RDD are dropped.
   * If a value from the left RDD does not overlap any values in the right
   * RDD, it will be paired with an empty Iterable in the product of the join.
   * SparkR friendly variant.
   *
   * @param genomicRdd The right RDD in the join.
   * @param flankSize Sets a flankSize for the distance between elements to be
   *   joined. If set to 0, an overlap is required to join two elements.
   * @return Returns a new genomic RDD containing all pairs of keys that
   *   overlapped in the genomic coordinate space, and all keys from the
   *   left RDD that did not overlap a key in the right RDD.
   */
  def leftOuterShuffleRegionJoinAndGroupByLeft[X, Y <: Product, Z <: GenomicDataset[X, Y, Z]](
    genomicRdd: GenomicDataset[X, Y, Z],
    flankSize: java.lang.Double): GenericGenomicDataset[(T, Iterable[X]), (U, Seq[Y])] = {

    leftOuterShuffleRegionJoinAndGroupByLeft(genomicRdd, flankSize.toInt: java.lang.Integer)
  }

  /**
   * Performs a sort-merge left outer join between this RDD and another RDD,
   * followed by a groupBy on the left value.
   *
   * In a sort-merge join, both RDDs are co-partitioned and sorted. The
   * partitions are then zipped, and we do a merge join on each partition.
   * The key equality function used for this join is the reference region
   * overlap function. Since this is a left outer join, all values in the
   * right RDD that do not overlap a value from the left RDD are dropped.
   * If a value from the left RDD does not overlap any values in the right
   * RDD, it will be paired with an empty Iterable in the product of the join.
   * PySpark/Java friendly variant.
   *
   * @param genomicRdd The right RDD in the join.
   * @param flankSize Sets a flankSize for the distance between elements to be
   *   joined. If set to 0, an overlap is required to join two elements.
   * @return Returns a new genomic RDD containing all pairs of keys that
   *   overlapped in the genomic coordinate space, and all keys from the
   *   left RDD that did not overlap a key in the right RDD.
   */
  def leftOuterShuffleRegionJoinAndGroupByLeft[X, Y <: Product, Z <: GenomicDataset[X, Y, Z]](
    genomicRdd: GenomicDataset[X, Y, Z],
    flankSize: java.lang.Integer): GenericGenomicDataset[(T, Iterable[X]), (U, Seq[Y])] = {

    implicit val tTag = ClassTag.AnyRef.asInstanceOf[ClassTag[T]]
    implicit val xTag = ClassTag.AnyRef.asInstanceOf[ClassTag[X]]
    implicit val txTag = ClassTag.AnyRef.asInstanceOf[ClassTag[(T, Iterable[X])]]
    implicit val u1Tag: TypeTag[U] = uTag
    implicit val u2Tag: TypeTag[Y] = genomicRdd.uTag
    implicit val uyTag = typeTag[(U, Seq[Y])]

    leftOuterShuffleRegionJoinAndGroupByLeft(genomicRdd, flankSize.toLong)
  }

  /**
   * Performs a sort-merge left outer join between this RDD and another RDD,
   * followed by a groupBy on the left value.
   *
   * In a sort-merge join, both RDDs are co-partitioned and sorted. The
   * partitions are then zipped, and we do a merge join on each partition.
   * The key equality function used for this join is the reference region
   * overlap function. Since this is a left outer join, all values in the
   * right RDD that do not overlap a value from the left RDD are dropped.
   * If a value from the left RDD does not overlap any values in the right
   * RDD, it will be paired with an empty Iterable in the product of the join.
   *
   * @param genomicRdd The right RDD in the join.
   * @param optPartitions Optionally sets the number of output partitions. If
   *   None, the number of partitions on the resulting RDD does not change.
   * @param flankSize Sets a flankSize for the distance between elements to be
   *   joined. If set to 0, an overlap is required to join two elements.
   * @return Returns a new genomic RDD containing all pairs of keys that
   *   overlapped in the genomic coordinate space, and all keys from the
   *   left RDD that did not overlap a key in the right RDD.
   */
  private[rdd] def leftOuterShuffleRegionJoinAndGroupByLeft[X, Y <: Product, Z <: GenomicDataset[X, Y, Z]](
    genomicRdd: GenomicDataset[X, Y, Z],
    optPartitions: Option[Int],
    flankSize: Long)(
      implicit tTag: ClassTag[T],
      xTag: ClassTag[X],
      toxTag: ClassTag[(T, Iterable[X])],
      uiyTag: TypeTag[(U, Seq[Y])]): GenericGenomicDataset[(T, Iterable[X]), (U, Seq[Y])] = LeftOuterShuffleJoin.time {

    val (leftRddToJoin, rightRddToJoin) =
      prepareForShuffleRegionJoin(genomicRdd, optPartitions, flankSize)

    // what sequences do we wind up with at the end?
    val combinedSequences = sequences ++ genomicRdd.sequences

    RDDBoundGenericGenomicDataset[(T, Iterable[X]), (U, Seq[Y])](
      LeftOuterShuffleRegionJoinAndGroupByLeft[T, X](leftRddToJoin, rightRddToJoin)
        .compute(),
      combinedSequences,
      GenericConverter[(T, Iterable[X]), (U, Seq[Y])](kv => {
        // pad by -1 * flankSize to undo flank from preprocessing
        getReferenceRegions(kv._1).map(_.pad(-1 * flankSize)) ++
          Seq(kv._2.map(v => genomicRdd.getReferenceRegions(v))).flatten.flatten
      },
        kv => (productFn(kv._1), kv._2.map(genomicRdd.productFn).toSeq),
        kv => (unproductFn(kv._1), kv._2.map(genomicRdd.unproductFn))),
      TagHolder[(T, Iterable[X]), (U, Seq[Y])]())
  }

  /**
   * Performs a sort-merge left outer join between this RDD and another RDD,
   * followed by a groupBy on the left value.
   *
   * In a sort-merge join, both RDDs are co-partitioned and sorted. The
   * partitions are then zipped, and we do a merge join on each partition.
   * The key equality function used for this join is the reference region
   * overlap function. Since this is a left outer join, all values in the
   * right RDD that do not overlap a value from the left RDD are dropped.
   * If a value from the left RDD does not overlap any values in the right
   * RDD, it will be paired with an empty Iterable in the product of the join.
   *
   * @param genomicRdd The right RDD in the join.
   * @param flankSize Sets a flankSize for the distance between elements to be
   *   joined. If set to 0, an overlap is required to join two elements.
   * @return Returns a new genomic RDD containing all pairs of keys that
   *   overlapped in the genomic coordinate space, and all keys from the
   *   left RDD that did not overlap a key in the right RDD.
   */
  def leftOuterShuffleRegionJoinAndGroupByLeft[X, Y <: Product, Z <: GenomicDataset[X, Y, Z]](
    genomicRdd: GenomicDataset[X, Y, Z],
    flankSize: Long)(
      implicit tTag: ClassTag[T],
      xTag: ClassTag[X],
      toxTag: ClassTag[(T, Iterable[X])],
      uiyTag: TypeTag[(U, Seq[Y])]): GenericGenomicDataset[(T, Iterable[X]), (U, Seq[Y])] = {

    leftOuterShuffleRegionJoinAndGroupByLeft(genomicRdd, None, flankSize)
  }

  /**
   * Performs a sort-merge left outer join between this RDD and another RDD,
   * followed by a groupBy on the left value.
   *
   * In a sort-merge join, both RDDs are co-partitioned and sorted. The
   * partitions are then zipped, and we do a merge join on each partition.
   * The key equality function used for this join is the reference region
   * overlap function. Since this is a left outer join, all values in the
   * right RDD that do not overlap a value from the left RDD are dropped.
   * If a value from the left RDD does not overlap any values in the right
   * RDD, it will be paired with an empty Iterable in the product of the join.
   *
   * @param genomicRdd The right RDD in the join.
   * @return Returns a new genomic RDD containing all pairs of keys that
   *   overlapped in the genomic coordinate space, and all keys from the
   *   left RDD that did not overlap a key in the right RDD.
   */
  def leftOuterShuffleRegionJoinAndGroupByLeft[X, Y <: Product, Z <: GenomicDataset[X, Y, Z]](
    genomicRdd: GenomicDataset[X, Y, Z])(
      implicit tTag: ClassTag[T],
      xTag: ClassTag[X],
      toxTag: ClassTag[(T, Iterable[X])],
      uiyTag: TypeTag[(U, Seq[Y])]): GenericGenomicDataset[(T, Iterable[X]), (U, Seq[Y])] = {

    leftOuterShuffleRegionJoinAndGroupByLeft(genomicRdd, None, 0L)
  }

  /**
   * Performs a sort-merge full outer join between this RDD and another RDD.
   *
   * In a sort-merge join, both RDDs are co-partitioned and sorted. The
   * partitions are then zipped, and we do a merge join on each partition.
   * The key equality function used for this join is the reference region
   * overlap function. Since this is a full outer join, if a value from either
   * RDD does not overlap any values in the other RDD, it will be paired with
   * a `None` in the product of the join. SparkR friendly variant.
   *
   * @param genomicRdd The right RDD in the join.
   * @param flankSize Sets a flankSize for the distance between elements to be
   *   joined. If set to 0, an overlap is required to join two elements.
   * @return Returns a new genomic RDD containing all pairs of keys that
   *   overlapped in the genomic coordinate space, and values that did not
   *   overlap will be paired with a `None`.
   */
  def fullOuterShuffleRegionJoin[X, Y <: Product, Z <: GenomicDataset[X, Y, Z]](
    genomicRdd: GenomicDataset[X, Y, Z],
    flankSize: java.lang.Double): GenericGenomicDataset[(Option[T], Option[X]), (Option[U], Option[Y])] = {

    fullOuterShuffleRegionJoin(genomicRdd, flankSize.toInt: java.lang.Integer)
  }

  /**
   * Performs a sort-merge full outer join between this RDD and another RDD.
   *
   * In a sort-merge join, both RDDs are co-partitioned and sorted. The
   * partitions are then zipped, and we do a merge join on each partition.
   * The key equality function used for this join is the reference region
   * overlap function. Since this is a full outer join, if a value from either
   * RDD does not overlap any values in the other RDD, it will be paired with
   * a `None` in the product of the join. PySpark/Java friendly variant.
   *
   * @param genomicRdd The right RDD in the join.
   * @param flankSize Sets a flankSize for the distance between elements to be
   *   joined. If set to 0, an overlap is required to join two elements.
   * @return Returns a new genomic RDD containing all pairs of keys that
   *   overlapped in the genomic coordinate space, and values that did not
   *   overlap will be paired with a `None`.
   */
  def fullOuterShuffleRegionJoin[X, Y <: Product, Z <: GenomicDataset[X, Y, Z]](
    genomicRdd: GenomicDataset[X, Y, Z],
    flankSize: java.lang.Integer): GenericGenomicDataset[(Option[T], Option[X]), (Option[U], Option[Y])] = {

    implicit val tTag = ClassTag.AnyRef.asInstanceOf[ClassTag[T]]
    implicit val xTag = ClassTag.AnyRef.asInstanceOf[ClassTag[X]]
    implicit val txTag = ClassTag.AnyRef.asInstanceOf[ClassTag[(Option[T], Option[X])]]
    implicit val u1Tag: TypeTag[U] = uTag
    implicit val u2Tag: TypeTag[Y] = genomicRdd.uTag
    implicit val uyTag = typeTag[(Option[U], Option[Y])]

    fullOuterShuffleRegionJoin(genomicRdd, flankSize.toLong)
  }

  /**
   * Performs a sort-merge full outer join between this RDD and another RDD.
   *
   * In a sort-merge join, both RDDs are co-partitioned and sorted. The
   * partitions are then zipped, and we do a merge join on each partition.
   * The key equality function used for this join is the reference region
   * overlap function. Since this is a full outer join, if a value from either
   * RDD does not overlap any values in the other RDD, it will be paired with
   * a `None` in the product of the join.
   *
   * @param genomicRdd The right RDD in the join.
   * @param optPartitions Optionally sets the number of output partitions. If
   *   None, the number of partitions on the resulting RDD does not change.
   * @param flankSize Sets a flankSize for the distance between elements to be
   *   joined. If set to 0, an overlap is required to join two elements.
   * @return Returns a new genomic RDD containing all pairs of keys that
   *   overlapped in the genomic coordinate space, and values that did not
   *   overlap will be paired with a `None`.
   */
  private[rdd] def fullOuterShuffleRegionJoin[X, Y <: Product, Z <: GenomicDataset[X, Y, Z]](
    genomicRdd: GenomicDataset[X, Y, Z],
    optPartitions: Option[Int],
    flankSize: Long)(
      implicit tTag: ClassTag[T],
      xTag: ClassTag[X],
      otoxTag: ClassTag[(Option[T], Option[X])],
      ouoyTag: TypeTag[(Option[U], Option[Y])]): GenericGenomicDataset[(Option[T], Option[X]), (Option[U], Option[Y])] = FullOuterShuffleJoin.time {

    val (leftRddToJoin, rightRddToJoin) =
      prepareForShuffleRegionJoin(genomicRdd, optPartitions, flankSize)

    // what sequences do we wind up with at the end?
    val combinedSequences = sequences ++ genomicRdd.sequences

    RDDBoundGenericGenomicDataset[(Option[T], Option[X]), (Option[U], Option[Y])](
      FullOuterShuffleRegionJoin[T, X](leftRddToJoin, rightRddToJoin)
        .compute(),
      combinedSequences,
      GenericConverter[(Option[T], Option[X]), (Option[U], Option[Y])](kv => {
        // pad by -1 * flankSize to undo pad from preprocessing
        Seq(kv._1.map(v => getReferenceRegions(v).map(_.pad(-1 * flankSize))),
          kv._2.map(v => genomicRdd.getReferenceRegions(v))).flatten.flatten
      },
        kv => (kv._1.map(productFn), kv._2.map(genomicRdd.productFn)),
        kv => (kv._1.map(unproductFn), kv._2.map(genomicRdd.unproductFn))),
      TagHolder[(Option[T], Option[X]), (Option[U], Option[Y])]())
  }

  /**
   * Performs a sort-merge full outer join between this RDD and another RDD.
   *
   * In a sort-merge join, both RDDs are co-partitioned and sorted. The
   * partitions are then zipped, and we do a merge join on each partition.
   * The key equality function used for this join is the reference region
   * overlap function. Since this is a full outer join, if a value from either
   * RDD does not overlap any values in the other RDD, it will be paired with
   * a `None` in the product of the join.
   *
   * @param genomicRdd The right RDD in the join.
   * @param flankSize Sets a flankSize for the distance between elements to be
   *   joined. If set to 0, an overlap is required to join two elements.
   * @return Returns a new genomic RDD containing all pairs of keys that
   *   overlapped in the genomic coordinate space, and values that did not
   *   overlap will be paired with a `None`.
   */
  def fullOuterShuffleRegionJoin[X, Y <: Product, Z <: GenomicDataset[X, Y, Z]](
    genomicRdd: GenomicDataset[X, Y, Z],
    flankSize: Long)(
      implicit tTag: ClassTag[T],
      xTag: ClassTag[X],
      otoxTag: ClassTag[(Option[T], Option[X])],
      ouoyTag: TypeTag[(Option[U], Option[Y])]): GenericGenomicDataset[(Option[T], Option[X]), (Option[U], Option[Y])] = {

    fullOuterShuffleRegionJoin(genomicRdd, None, flankSize)
  }

  /**
   * Performs a sort-merge full outer join between this RDD and another RDD.
   *
   * In a sort-merge join, both RDDs are co-partitioned and sorted. The
   * partitions are then zipped, and we do a merge join on each partition.
   * The key equality function used for this join is the reference region
   * overlap function. Since this is a full outer join, if a value from either
   * RDD does not overlap any values in the other RDD, it will be paired with
   * a `None` in the product of the join.
   *
   * @param genomicRdd The right RDD in the join.
   * @return Returns a new genomic RDD containing all pairs of keys that
   *   overlapped in the genomic coordinate space, and values that did not
   *   overlap will be paired with a `None`.
   */
  def fullOuterShuffleRegionJoin[X, Y <: Product, Z <: GenomicDataset[X, Y, Z]](
    genomicRdd: GenomicDataset[X, Y, Z])(
      implicit tTag: ClassTag[T],
      xTag: ClassTag[X],
      otoxTag: ClassTag[(Option[T], Option[X])],
      ouoyTag: TypeTag[(Option[U], Option[Y])]): GenericGenomicDataset[(Option[T], Option[X]), (Option[U], Option[Y])] = {

    fullOuterShuffleRegionJoin(genomicRdd, None, 0L)
  }

  /**
   * Performs a sort-merge inner join between this RDD and another RDD,
   * followed by a groupBy on the left value.
   *
   * In a sort-merge join, both RDDs are co-partitioned and sorted. The
   * partitions are then zipped, and we do a merge join on each partition.
   * The key equality function used for this join is the reference region
   * overlap function. In the same operation, we group all values by the left
   * item in the RDD. SparkR friendly variant.
   *
   * @param genomicRdd The right RDD in the join.
   * @param flankSize Sets a flankSize for the distance between elements to be
   *   joined. If set to 0, an overlap is required to join two elements.
   * @return Returns a new genomic RDD containing all pairs of keys that
   *   overlapped in the genomic coordinate space, grouped together by
   *   the value they overlapped in the left RDD.
   */
  def shuffleRegionJoinAndGroupByLeft[X, Y <: Product, Z <: GenomicDataset[X, Y, Z]](
    genomicRdd: GenomicDataset[X, Y, Z],
    flankSize: java.lang.Double): GenericGenomicDataset[(T, Iterable[X]), (U, Seq[Y])] = {

    shuffleRegionJoinAndGroupByLeft(genomicRdd, flankSize.toInt: java.lang.Integer)
  }

  /**
   * Performs a sort-merge inner join between this RDD and another RDD,
   * followed by a groupBy on the left value.
   *
   * In a sort-merge join, both RDDs are co-partitioned and sorted. The
   * partitions are then zipped, and we do a merge join on each partition.
   * The key equality function used for this join is the reference region
   * overlap function. In the same operation, we group all values by the left
   * item in the RDD. PySpark/Java friendly variant.
   *
   * @param genomicRdd The right RDD in the join.
   * @param flankSize Sets a flankSize for the distance between elements to be
   *   joined. If set to 0, an overlap is required to join two elements.
   * @return Returns a new genomic RDD containing all pairs of keys that
   *   overlapped in the genomic coordinate space, grouped together by
   *   the value they overlapped in the left RDD.
   */
  def shuffleRegionJoinAndGroupByLeft[X, Y <: Product, Z <: GenomicDataset[X, Y, Z]](
    genomicRdd: GenomicDataset[X, Y, Z],
    flankSize: java.lang.Integer): GenericGenomicDataset[(T, Iterable[X]), (U, Seq[Y])] = {

    implicit val tTag = ClassTag.AnyRef.asInstanceOf[ClassTag[T]]
    implicit val xTag = ClassTag.AnyRef.asInstanceOf[ClassTag[X]]
    implicit val txTag = ClassTag.AnyRef.asInstanceOf[ClassTag[(T, Iterable[X])]]
    implicit val u1Tag: TypeTag[U] = uTag
    implicit val u2Tag: TypeTag[Y] = genomicRdd.uTag
    implicit val uyTag = typeTag[(U, Seq[Y])]

    shuffleRegionJoinAndGroupByLeft(genomicRdd, flankSize.toLong)
  }

  /**
   * Performs a sort-merge inner join between this RDD and another RDD,
   * followed by a groupBy on the left value.
   *
   * In a sort-merge join, both RDDs are co-partitioned and sorted. The
   * partitions are then zipped, and we do a merge join on each partition.
   * The key equality function used for this join is the reference region
   * overlap function. Since this is an inner join, all values who do not
   * overlap a value from the other RDD are dropped. In the same operation,
   * we group all values by the left item in the RDD.
   *
   * @param genomicRdd The right RDD in the join.
   * @param optPartitions Optionally sets the number of output partitions. If
   *   None, the number of partitions on the resulting RDD does not change.
   * @param flankSize Sets a flankSize for the distance between elements to be
   *   joined. If set to 0, an overlap is required to join two elements.
   * @return Returns a new genomic RDD containing all pairs of keys that
   *   overlapped in the genomic coordinate space, grouped together by
   *   the value they overlapped in the left RDD..
   */
  private[rdd] def shuffleRegionJoinAndGroupByLeft[X, Y <: Product, Z <: GenomicDataset[X, Y, Z]](
    genomicRdd: GenomicDataset[X, Y, Z],
    optPartitions: Option[Int],
    flankSize: Long)(
      implicit tTag: ClassTag[T],
      xTag: ClassTag[X],
      tixTag: ClassTag[(T, Iterable[X])],
      uiyTag: TypeTag[(U, Seq[Y])]): GenericGenomicDataset[(T, Iterable[X]), (U, Seq[Y])] = ShuffleJoinAndGroupByLeft.time {

    val (leftRddToJoin, rightRddToJoin) =
      prepareForShuffleRegionJoin(genomicRdd, optPartitions, flankSize)

    // what sequences do we wind up with at the end?
    val combinedSequences = sequences ++ genomicRdd.sequences

    RDDBoundGenericGenomicDataset[(T, Iterable[X]), (U, Seq[Y])](
      InnerShuffleRegionJoinAndGroupByLeft[T, X](leftRddToJoin, rightRddToJoin)
        .compute(),
      combinedSequences,
      GenericConverter[(T, Iterable[X]), (U, Seq[Y])](kv => {
        // pad by -1 * flankSize to undo pad from preprocessing
        getReferenceRegions(kv._1)
          .map(_.pad(-1 * flankSize)) ++
          kv._2.flatMap(v => genomicRdd.getReferenceRegions(v))
      },
        kv => (productFn(kv._1), kv._2.map(genomicRdd.productFn).toSeq),
        kv => (unproductFn(kv._1), kv._2.map(genomicRdd.unproductFn))),
      TagHolder[(T, Iterable[X]), (U, Seq[Y])]())
  }

  /**
   * Performs a sort-merge inner join between this RDD and another RDD,
   * followed by a groupBy on the left value.
   *
   * In a sort-merge join, both RDDs are co-partitioned and sorted. The
   * partitions are then zipped, and we do a merge join on each partition.
   * The key equality function used for this join is the reference region
   * overlap function. Since this is an inner join, all values who do not
   * overlap a value from the other RDD are dropped. In the same operation,
   * we group all values by the left item in the RDD.
   *
   * @param genomicRdd The right RDD in the join.
   * @param flankSize Sets a flankSize for the distance between elements to be
   *   joined. If set to 0, an overlap is required to join two elements.
   * @return Returns a new genomic RDD containing all pairs of keys that
   *   overlapped in the genomic coordinate space, grouped together by
   *   the value they overlapped in the left RDD..
   */
  def shuffleRegionJoinAndGroupByLeft[X, Y <: Product, Z <: GenomicDataset[X, Y, Z]](
    genomicRdd: GenomicDataset[X, Y, Z],
    flankSize: Long)(
      implicit tTag: ClassTag[T],
      xTag: ClassTag[X],
      tixTag: ClassTag[(T, Iterable[X])],
      uiyTag: TypeTag[(U, Seq[Y])]): GenericGenomicDataset[(T, Iterable[X]), (U, Seq[Y])] = {

    shuffleRegionJoinAndGroupByLeft(genomicRdd, None, flankSize)
  }

  /**
   * Performs a sort-merge inner join between this RDD and another RDD,
   * followed by a groupBy on the left value.
   *
   * In a sort-merge join, both RDDs are co-partitioned and sorted. The
   * partitions are then zipped, and we do a merge join on each partition.
   * The key equality function used for this join is the reference region
   * overlap function. Since this is an inner join, all values who do not
   * overlap a value from the other RDD are dropped. In the same operation,
   * we group all values by the left item in the RDD.
   *
   * @param genomicRdd The right RDD in the join.
   * @return Returns a new genomic RDD containing all pairs of keys that
   *   overlapped in the genomic coordinate space, grouped together by
   *   the value they overlapped in the left RDD..
   */
  def shuffleRegionJoinAndGroupByLeft[X, Y <: Product, Z <: GenomicDataset[X, Y, Z]](
    genomicRdd: GenomicDataset[X, Y, Z])(
      implicit tTag: ClassTag[T],
      xTag: ClassTag[X],
      tixTag: ClassTag[(T, Iterable[X])],
      uiyTag: TypeTag[(U, Seq[Y])]): GenericGenomicDataset[(T, Iterable[X]), (U, Seq[Y])] = {

    shuffleRegionJoinAndGroupByLeft(genomicRdd, None, 0L)
  }

  /**
   * Performs a sort-merge right outer join between this RDD and another RDD,
   * followed by a groupBy on the left value, if not null.
   *
   * In a sort-merge join, both RDDs are co-partitioned and sorted. The
   * partitions are then zipped, and we do a merge join on each partition.
   * The key equality function used for this join is the reference region
   * overlap function. In the same operation, we group all values by the left
   * item in the RDD. Since this is a right outer join, all values from the
   * right RDD who did not overlap a value from the left RDD are placed into
   * a length-1 Iterable with a `None` key. SparkR friendly variant.
   *
   * @param genomicRdd The right RDD in the join.
   * @param flankSize Sets a flankSize for the distance between elements to be
   *   joined. If set to 0, an overlap is required to join two elements.
   * @return Returns a new genomic RDD containing all pairs of keys that
   *   overlapped in the genomic coordinate space, grouped together by
   *   the value they overlapped in the left RDD, and all values from the
   *   right RDD that did not overlap an item in the left RDD.
   */
  def rightOuterShuffleRegionJoinAndGroupByLeft[X, Y <: Product, Z <: GenomicDataset[X, Y, Z]](
    genomicRdd: GenomicDataset[X, Y, Z],
    flankSize: java.lang.Double): GenericGenomicDataset[(Option[T], Iterable[X]), (Option[U], Seq[Y])] = {

    rightOuterShuffleRegionJoinAndGroupByLeft(genomicRdd, flankSize.toInt: java.lang.Integer)
  }

  /**
   * Performs a sort-merge right outer join between this RDD and another RDD,
   * followed by a groupBy on the left value, if not null.
   *
   * In a sort-merge join, both RDDs are co-partitioned and sorted. The
   * partitions are then zipped, and we do a merge join on each partition.
   * The key equality function used for this join is the reference region
   * overlap function. In the same operation, we group all values by the left
   * item in the RDD. Since this is a right outer join, all values from the
   * right RDD who did not overlap a value from the left RDD are placed into
   * a length-1 Iterable with a `None` key. PySpark/Java friendly variant.
   *
   * @param genomicRdd The right RDD in the join.
   * @param flankSize Sets a flankSize for the distance between elements to be
   *   joined. If set to 0, an overlap is required to join two elements.
   * @return Returns a new genomic RDD containing all pairs of keys that
   *   overlapped in the genomic coordinate space, grouped together by
   *   the value they overlapped in the left RDD, and all values from the
   *   right RDD that did not overlap an item in the left RDD.
   */
  def rightOuterShuffleRegionJoinAndGroupByLeft[X, Y <: Product, Z <: GenomicDataset[X, Y, Z]](
    genomicRdd: GenomicDataset[X, Y, Z],
    flankSize: java.lang.Integer): GenericGenomicDataset[(Option[T], Iterable[X]), (Option[U], Seq[Y])] = {

    implicit val tTag = ClassTag.AnyRef.asInstanceOf[ClassTag[T]]
    implicit val xTag = ClassTag.AnyRef.asInstanceOf[ClassTag[X]]
    implicit val txTag = ClassTag.AnyRef.asInstanceOf[ClassTag[(Option[T], Iterable[X])]]
    implicit val u1Tag: TypeTag[U] = uTag
    implicit val u2Tag: TypeTag[Y] = genomicRdd.uTag
    implicit val uyTag = typeTag[(Option[U], Seq[Y])]

    rightOuterShuffleRegionJoinAndGroupByLeft(genomicRdd, flankSize.toLong)
  }

  /**
   * Performs a sort-merge right outer join between this RDD and another RDD,
   * followed by a groupBy on the left value, if not null.
   *
   * In a sort-merge join, both RDDs are co-partitioned and sorted. The
   * partitions are then zipped, and we do a merge join on each partition.
   * The key equality function used for this join is the reference region
   * overlap function. In the same operation, we group all values by the left
   * item in the RDD. Since this is a right outer join, all values from the
   * right RDD who did not overlap a value from the left RDD are placed into
   * a length-1 Iterable with a `None` key.
   *
   * @param genomicRdd The right RDD in the join.
   * @param optPartitions Optionally sets the number of output partitions. If
   *   None, the number of partitions on the resulting RDD does not change.
   * @param flankSize Sets a flankSize for the distance between elements to be
   *   joined. If set to 0, an overlap is required to join two elements.
   * @return Returns a new genomic RDD containing all pairs of keys that
   *   overlapped in the genomic coordinate space, grouped together by
   *   the value they overlapped in the left RDD, and all values from the
   *   right RDD that did not overlap an item in the left RDD.
   */
  private[rdd] def rightOuterShuffleRegionJoinAndGroupByLeft[X, Y <: Product, Z <: GenomicDataset[X, Y, Z]](
    genomicRdd: GenomicDataset[X, Y, Z],
    optPartitions: Option[Int],
    flankSize: Long)(
      implicit tTag: ClassTag[T],
      xTag: ClassTag[X],
      otixTag: ClassTag[(Option[T], Iterable[X])],
      ouiyTag: TypeTag[(Option[U], Seq[Y])]): GenericGenomicDataset[(Option[T], Iterable[X]), (Option[U], Seq[Y])] = RightOuterShuffleJoinAndGroupByLeft.time {

    val (leftRddToJoin, rightRddToJoin) =
      prepareForShuffleRegionJoin(genomicRdd, optPartitions, flankSize)

    // what sequences do we wind up with at the end?
    val combinedSequences = sequences ++ genomicRdd.sequences

    RDDBoundGenericGenomicDataset[(Option[T], Iterable[X]), (Option[U], Seq[Y])](
      RightOuterShuffleRegionJoinAndGroupByLeft[T, X](leftRddToJoin, rightRddToJoin)
        .compute(),
      combinedSequences,
      GenericConverter[(Option[T], Iterable[X]), (Option[U], Seq[Y])](kv => {
        // pad by -1 * flankSize to undo pad from preprocessing
        kv._1.toSeq.flatMap(v => getReferenceRegions(v)
          .map(_.pad(-1 * flankSize))) ++
          kv._2.flatMap(v => genomicRdd.getReferenceRegions(v))
      },
        kv => (kv._1.map(productFn), kv._2.map(genomicRdd.productFn).toSeq),
        kv => (kv._1.map(unproductFn), kv._2.map(genomicRdd.unproductFn))),
      TagHolder[(Option[T], Iterable[X]), (Option[U], Seq[Y])]())
  }

  /**
   * Performs a sort-merge right outer join between this RDD and another RDD,
   * followed by a groupBy on the left value, if not null.
   *
   * In a sort-merge join, both RDDs are co-partitioned and sorted. The
   * partitions are then zipped, and we do a merge join on each partition.
   * The key equality function used for this join is the reference region
   * overlap function. In the same operation, we group all values by the left
   * item in the RDD. Since this is a right outer join, all values from the
   * right RDD who did not overlap a value from the left RDD are placed into
   * a length-1 Iterable with a `None` key.
   *
   * @param genomicRdd The right RDD in the join.
   * @param flankSize Sets a flankSize for the distance between elements to be
   *   joined. If set to 0, an overlap is required to join two elements.
   * @return Returns a new genomic RDD containing all pairs of keys that
   *   overlapped in the genomic coordinate space, grouped together by
   *   the value they overlapped in the left RDD, and all values from the
   *   right RDD that did not overlap an item in the left RDD.
   */
  def rightOuterShuffleRegionJoinAndGroupByLeft[X, Y <: Product, Z <: GenomicDataset[X, Y, Z]](
    genomicRdd: GenomicDataset[X, Y, Z],
    flankSize: Long)(
      implicit tTag: ClassTag[T],
      xTag: ClassTag[X],
      otixTag: ClassTag[(Option[T], Iterable[X])],
      ousyTag: TypeTag[(Option[U], Seq[Y])]): GenericGenomicDataset[(Option[T], Iterable[X]), (Option[U], Seq[Y])] = {

    rightOuterShuffleRegionJoinAndGroupByLeft(genomicRdd, None, flankSize)
  }

  /**
   * Performs a sort-merge right outer join between this RDD and another RDD,
   * followed by a groupBy on the left value, if not null.
   *
   * In a sort-merge join, both RDDs are co-partitioned and sorted. The
   * partitions are then zipped, and we do a merge join on each partition.
   * The key equality function used for this join is the reference region
   * overlap function. In the same operation, we group all values by the left
   * item in the RDD. Since this is a right outer join, all values from the
   * right RDD who did not overlap a value from the left RDD are placed into
   * a length-1 Iterable with a `None` key.
   *
   * @param genomicRdd The right RDD in the join.
   * @return Returns a new genomic RDD containing all pairs of keys that
   *   overlapped in the genomic coordinate space, grouped together by
   *   the value they overlapped in the left RDD, and all values from the
   *   right RDD that did not overlap an item in the left RDD.
   */
  def rightOuterShuffleRegionJoinAndGroupByLeft[X, Y <: Product, Z <: GenomicDataset[X, Y, Z]](
    genomicRdd: GenomicDataset[X, Y, Z])(
      implicit tTag: ClassTag[T],
      xTag: ClassTag[X],
      otixTag: ClassTag[(Option[T], Iterable[X])],
      otsyTag: TypeTag[(Option[U], Seq[Y])]): GenericGenomicDataset[(Option[T], Iterable[X]), (Option[U], Seq[Y])] = {

    rightOuterShuffleRegionJoinAndGroupByLeft(genomicRdd, None, 0L)
  }

  /**
   * Copartitions two RDDs according to their ReferenceRegions.
   *
   * @note This is best used under the condition that (repeatedly)
   *   repartitioning is more expensive than calculating the proper location
   *   of the records of this.rdd. It requires a pass through the co-located
   *   RDD to get the correct partition(s) for each record. It will assign a
   *   record to multiple partitions if necessary.
   *
   * @param rddToCoPartitionWith The rdd to copartition to.
   * @return The newly repartitioned rdd.
   */
  private[rdd] def copartitionByReferenceRegion[X, Y <: Product, Z <: GenomicDataset[X, Y, Z]](
    rddToCoPartitionWith: GenomicDataset[X, Y, Z],
    flankSize: Long = 0L)(implicit tTag: ClassTag[T], xTag: ClassTag[X]): V = {

    // if the other RDD is not sorted, we can't guarantee proper copartition
    assert(rddToCoPartitionWith.isSorted,
      "Cannot copartition with an unsorted rdd!")

    val destinationPartitionMap = rddToCoPartitionWith.optPartitionMap.get

    // number of partitions we will have after repartition
    val numPartitions = destinationPartitionMap.length

    val partitionRegionsAndIndices: Map[String, Seq[(ReferenceRegion, Int)]] = {

      // the zipWithIndex gives us the destination partition ID
      val pMap: Map[String, Seq[(ReferenceRegion, Int)]] =
        destinationPartitionMap.zipWithIndex.flatMap(g => {
          val (optRegions, index) = (g._1, g._2)

          optRegions.map(rp => {
            val (firstRegion, secondRegion) = rp

            // in the case where we span multiple referenceNames using
            // IntervalArray.get with requireOverlap set to false will assign all
            // the remaining regions to this partition, in addition to all the
            // regions up to the start of the next partition.
            if (firstRegion.referenceName != secondRegion.referenceName) {

              // this partition covers the end of one contig and the
              // start of another
              Iterable((ReferenceRegion.toEnd(firstRegion.referenceName,
                firstRegion.start), index),
                (ReferenceRegion.fromStart(secondRegion.referenceName,
                  secondRegion.end), index))
            } else {
              // otherwise we just have the ReferenceRegion span from partition
              // lower bound to upper bound.
              // We cannot use the firstRegion bounds here because we may end up
              // dropping data if it doesn't map anywhere.
              Iterable((ReferenceRegion(
                firstRegion.referenceName,
                firstRegion.start,
                secondRegion.end),
                index))
            }
          })
        }).flatten
          .groupBy(_._1.referenceName)
          .map(p => {
            val (referenceName, indexedRegions) = p

            val regions: Seq[(ReferenceRegion, Int)] = if (indexedRegions.size == 1) {
              // if we only have a single partition for this contig, extend the
              // region to cover the whole contig
              Seq((ReferenceRegion.all(indexedRegions.head._1.referenceName),
                indexedRegions.head._2))
            } else {
              val sortedRegions = indexedRegions.sortBy(_._2)

              // if we have multiple partitions for this contig, extend the
              // first region to the start of the contig, and the last region to
              // the end of the contig
              sortedRegions.take(1).map(rp => {
                (ReferenceRegion.fromStart(rp._1.referenceName,
                  rp._1.end), rp._2)
              }) ++ sortedRegions.tail.dropRight(1) ++ sortedRegions.takeRight(1)
                .map(rp => {
                  (ReferenceRegion.toEnd(rp._1.referenceName,
                    rp._1.start), rp._2)
                })
            }

            (referenceName, regions)
          })

      // the above map does not contain sequences who exist in the sequence
      // dictionary but who are not seen in a record at the start/end of a
      // partition
      //
      // here, we loop over the sequence records, check if they are in the map,
      // and create a record if the sequence is not in the map
      var lastIdx = 0
      val missingSequences: Map[String, Seq[(ReferenceRegion, Int)]] =
        sequences.records
          .sortBy(_.name)
          .flatMap(sr => {
            pMap.get(sr.name)
              .fold(
                Some((sr.name -> Seq((ReferenceRegion.all(sr.name), lastIdx))))
                  .asInstanceOf[Option[(String, Seq[(ReferenceRegion, Int)])]]
              )(s => {
                  lastIdx = s.maxBy(_._2)._2
                  None.asInstanceOf[Option[(String, Seq[(ReferenceRegion, Int)])]]
                })
          }).toMap

      pMap ++ missingSequences
    }

    val finalPartitionedRDD = {
      val referenceRegionKeyedGenomicDataset = flattenRddByRegions()

      referenceRegionKeyedGenomicDataset.mapPartitions(iter => {
        iter.flatMap(f => {
          val paddedRegion = f._1.pad(flankSize)
          partitionRegionsAndIndices.get(paddedRegion.referenceName)
            .map(regionsWithIndices => {
              regionsWithIndices.dropWhile(!_._1.overlaps(paddedRegion))
                .takeWhile(_._1.overlaps(paddedRegion))
                .map(g => ((f._1, g._2), f._2))
            })
        }).flatten
      }, preservesPartitioning = true)
        .repartitionAndSortWithinPartitions(
          ManualRegionPartitioner(numPartitions))
    }

    replaceRdd(finalPartitionedRDD.values, rddToCoPartitionWith.optPartitionMap)
  }

  /**
   * Gets the partition bounds from a ReferenceRegion keyed Iterator.
   *
   * @param iter The data on a given partition. ReferenceRegion keyed.
   * @return The bounds of the ReferenceRegions on that partition, in an Iterator.
   */
  private def getRegionBoundsFromPartition(
    iter: Iterator[(ReferenceRegion, T)]): Iterator[Option[(ReferenceRegion, ReferenceRegion)]] = {

    if (iter.isEmpty) {
      // This means that there is no data on the partition, so we have no bounds
      Iterator(None)
    } else {
      val firstRegion = iter.next
      val lastRegion =
        if (iter.hasNext) {
          // we have to make sure we get the full bounds of this partition, this
          // includes any extremely long regions. we include the firstRegion for
          // the case that the first region is extremely long
          (iter ++ Iterator(firstRegion)).maxBy(f => (f._1.referenceName, f._1.end, f._1.start))
        } else {
          // only one record on this partition, so this is the extent of the bounds
          firstRegion
        }
      Iterator(Some((firstRegion._1, lastRegion._1)))
    }
  }

  /**
   * Writes an RDD to disk as text and optionally merges.
   *
   * @param rdd RDD to save.
   * @param outputPath Output path to save text files to.
   * @param asSingleFile If true, combines all partition shards.
   * @param disableFastConcat If asSingleFile is true, disables the use of the
   *   parallel file merging engine.
   * @param optHeaderPath If provided, the header file to include.
   */
  protected def writeTextRdd[T](rdd: RDD[T],
                                outputPath: String,
                                asSingleFile: Boolean,
                                disableFastConcat: Boolean,
                                optHeaderPath: Option[String] = None): Unit = {
    TextRddWriter.writeTextRdd(rdd, outputPath, asSingleFile, disableFastConcat, optHeaderPath)
  }
}

// we pass these conversion functions back and forth between the various
// generic genomic datset implementations, so it makes sense to bundle
// them up in a case class
case class GenericConverter[T, U] private (regionFn: T => Seq[ReferenceRegion],
                                           productFn: T => U,
                                           unproductFn: U => T) {
}

sealed abstract class GenericGenomicDataset[T, U <: Product] extends GenomicDataset[T, U, GenericGenomicDataset[T, U]] {

  protected val converter: GenericConverter[T, U]
  lazy val regionFn = converter.regionFn
  @transient lazy val productFn = converter.productFn
  @transient lazy val unproductFn = converter.unproductFn

  @transient val uTag: TypeTag[U]

  def saveAsParquet(filePath: String,
                    blockSize: Int = 128 * 1024 * 1024,
                    pageSize: Int = 1 * 1024 * 1024,
                    compressCodec: CompressionCodecName = CompressionCodecName.GZIP,
                    disableDictionaryEncoding: Boolean = false) {
    log.warn("Saving directly as Parquet from SQL. Options other than compression codec are ignored.")
    dataset.toDF()
      .write
      .format("parquet")
      .option("spark.sql.parquet.compression.codec", compressCodec.toString.toLowerCase())
      .save(filePath)
  }

  protected def buildTree(
    rdd: RDD[(ReferenceRegion, T)])(
      implicit tTag: ClassTag[T]): IntervalArray[ReferenceRegion, T] = {
    IntervalArray(rdd)
  }

  protected def getReferenceRegions(elem: T): Seq[ReferenceRegion] = {
    regionFn(elem)
  }
}

// this class is needed as a workaround to allow the generic genomic dataset
// classes to have no-arg constructors
//
// during conversion to dataset, the generic genomic dataset classes get
// swept up and serialized as part of serializing the task. this uses java
// serialization, which requires a no arg constructor. however, the generic
// genomic dataset classes also require classtags and typetags. this is
// problematic, as the way that the scala compiler supplies these is through
// arguments that are added to the constructor. there doesn't seem to be an
// obvious way to eliminate adding these to the constuctor, unless you remove
// the TypeTag view bound and find a way to supply the typetags and classtags
// through some other means.
//
// by making each generic genomic dataset instance have a instance of the
// tagholder class, we can work around this problem. what we do is remove the
// view bound on the generic genomic dataset classes, and provide a view bound
// on the tagholder class. wherever we need a classtag or typetag, we rely
// lazily on the tags available from the tagholder.
private[rdd] case class TagHolder[T, U <: Product: TypeTag]()(
    implicit val tTag: ClassTag[T],
    val uTag: ClassTag[U]) {
  @transient val uTTag: TypeTag[U] = typeTag[U]
}

case class DatasetBoundGenericGenomicDataset[T, U <: Product](
    dataset: Dataset[U],
    sequences: SequenceDictionary,
    converter: GenericConverter[T, U],
    tagHolder: TagHolder[T, U]) extends GenericGenomicDataset[T, U] {

  implicit val tTag: ClassTag[T] = tagHolder.tTag
  implicit val uCTag: ClassTag[U] = tagHolder.uTag
  @transient val uTag: TypeTag[U] = tagHolder.uTTag

  val optPartitionMap = None

  lazy val rdd: RDD[T] = {
    dataset.rdd.map(converter.unproductFn(_))
  }

  def replaceSequences(
    newSequences: SequenceDictionary): GenericGenomicDataset[T, U] = {
    copy(sequences = newSequences)
  }

  // this cannot be in the GenericGenomicDataset trait due to need for the
  // implicit classtag
  def union(rdds: GenericGenomicDataset[T, U]*): GenericGenomicDataset[T, U] = {
    val iterableRdds = rdds.toSeq
    RDDBoundGenericGenomicDataset(rdd.context.union(rdd, iterableRdds.map(_.rdd): _*),
      iterableRdds.map(_.sequences).fold(sequences)(_ ++ _),
      converter,
      tagHolder)
  }

  // this cannot be in the GenericGenomicDataset trait due to need for the
  // implicit classtag
  protected def replaceRdd(
    newRdd: RDD[T],
    newPartitionMap: Option[Array[Option[(ReferenceRegion, ReferenceRegion)]]] = None): GenericGenomicDataset[T, U] = {

    RDDBoundGenericGenomicDataset(newRdd,
      sequences,
      converter,
      tagHolder,
      optPartitionMap = newPartitionMap)
  }

  // this cannot be in the GenericGenomicDataset trait due to need for the
  // implicit classtag
  def transformDataset(tFn: Dataset[U] => Dataset[U]): GenericGenomicDataset[T, U] = {
    DatasetBoundGenericGenomicDataset(tFn(dataset),
      sequences,
      converter,
      tagHolder)
  }
}

case class RDDBoundGenericGenomicDataset[T, U <: Product](
    rdd: RDD[T],
    sequences: SequenceDictionary,
    converter: GenericConverter[T, U],
    @transient tagHolder: TagHolder[T, U],
    optPartitionMap: Option[Array[Option[(ReferenceRegion, ReferenceRegion)]]] = None) extends GenericGenomicDataset[T, U] {

  implicit val tTag: ClassTag[T] = tagHolder.tTag
  implicit val uCTag: ClassTag[U] = tagHolder.uTag
  @transient implicit val uTag: TypeTag[U] = tagHolder.uTTag

  def this() = {
    this(null, SequenceDictionary.empty, null, null, None)
  }

  lazy val dataset: Dataset[U] = {
    val sqlContext = SQLContext.getOrCreate(rdd.context)
    import sqlContext.implicits._
    val productRdd: RDD[U] = rdd.map(converter.productFn(_))
    sqlContext.createDataset(productRdd)
  }

  def replaceSequences(
    newSequences: SequenceDictionary): GenericGenomicDataset[T, U] = {
    copy(sequences = newSequences)
  }

  // this cannot be in the GenericGenomicDataset trait due to need for the
  // implicit classtag
  def union(rdds: GenericGenomicDataset[T, U]*): GenericGenomicDataset[T, U] = {
    val iterableRdds = rdds.toSeq
    RDDBoundGenericGenomicDataset(rdd.context.union(rdd, iterableRdds.map(_.rdd): _*),
      iterableRdds.map(_.sequences).fold(sequences)(_ ++ _),
      converter,
      tagHolder)
  }

  // this cannot be in the GenericGenomicDataset trait due to need for the
  // implicit classtag
  protected def replaceRdd(
    newRdd: RDD[T],
    newPartitionMap: Option[Array[Option[(ReferenceRegion, ReferenceRegion)]]] = None): GenericGenomicDataset[T, U] = {

    RDDBoundGenericGenomicDataset(newRdd,
      sequences,
      converter,
      tagHolder,
      optPartitionMap = newPartitionMap)
  }

  // this cannot be in the GenericGenomicDataset trait due to need for the
  // implicit classtag
  def transformDataset(tFn: Dataset[U] => Dataset[U]): GenericGenomicDataset[T, U] = {
    DatasetBoundGenericGenomicDataset(tFn(dataset),
      sequences,
      converter,
      tagHolder)
  }
}

/**
 * A trait describing a GenomicDataset with data from multiple samples.
 */
trait MultisampleGenomicDataset[T, U <: Product, V <: MultisampleGenomicDataset[T, U, V]] extends GenomicDataset[T, U, V] {

  /**
   * Save the samples to disk.
   *
   * @param filePath The filepath to the file where we will save the samples.
   */
  protected def saveSamples(filePath: String): Unit = {
    // get file to write to
    saveAvro("%s/_samples.avro".format(filePath),
      rdd.context,
      Sample.SCHEMA$,
      samples)
  }

  override def toString = "%s with %d reference sequences and %d samples"
    .format(getClass.getSimpleName, sequences.size, samples.size)

  /**
   * The samples who have data contained in this GenomicDataset.
   */
  val samples: Seq[Sample]

  /**
   * Replaces the sample metadata attached to the RDD.
   *
   * @param newSamples The new sample metadata to attach.
   * @return A GenomicDataset with new sample metadata.
   */
  def replaceSamples(newSamples: Iterable[Sample]): V

  /**
   * Adds samples to the current RDD.
   *
   * @param samplesToAdd Zero or more samples to add.
   * @return Returns a new RDD with samples added.
   */
  def addSamples(samplesToAdd: Iterable[Sample]): V = {
    replaceSamples(samples ++ samplesToAdd)
  }

  /**
   * Adds a single sample to the current RDD.
   *
   * @param sampleToAdd A single sample to add.
   * @return Returns a new RDD with this sample added.
   */
  def addSample(sampleToAdd: Sample): V = {
    addSamples(Seq(sampleToAdd))
  }
}

/**
 * A trait describing a GenomicDataset that is physically backed by a Dataset.
 */
trait DatasetBoundGenomicDataset[T, U <: Product, V <: GenomicDataset[T, U, V]] extends GenomicDataset[T, U, V] {

  val isPartitioned: Boolean
  val optPartitionBinSize: Option[Int]
  val optLookbackPartitions: Option[Int]

  override def cache(): V = {
    transformDataset(_.cache())
  }

  override def persist(sl: StorageLevel): V = {
    transformDataset(_.persist(sl))
  }

  override def unpersist(): V = {
    transformDataset(_.unpersist())
  }

  private def referenceRegionsToDatasetQueryString(regions: Iterable[ReferenceRegion]): String = {

    if (Try(dataset("positionBin")).isSuccess) {

      regions.map(r => {
        val startBin = (mathFloor(r.start / optPartitionBinSize.get).toLong) - optLookbackPartitions.get
        val endBin = min(Int.MaxValue.toLong, (mathFloor(r.end / optPartitionBinSize.get).toLong + 1)).toInt

        (s"(contigName=\'${r.referenceName}\' and positionBin >= " +
          s"\'${startBin}\'" +
          s" and positionBin < \'${endBin}\'" +
          s" and (end > ${r.start} and start < ${r.end}))")
      }).mkString(" or ")
    } else { // if no positionBin field is found then construct query without bin optimization
      regions.map(r =>
        s"(contigName=\'${r.referenceName} \' " +
          s"and (end > ${r.start} and start < ${r.end}))")
        .mkString(" or ")
    }
  }

  override def filterByOverlappingRegions(querys: Iterable[ReferenceRegion]): V = {
    if (isPartitioned) {
      transformDataset((d: Dataset[U]) =>
        d.filter(referenceRegionsToDatasetQueryString(querys)))
    } else {
      super[GenomicDataset].filterByOverlappingRegions(querys)
    }
  }
}

trait GenomicDatasetWithLineage[T, U <: Product, V <: GenomicDatasetWithLineage[T, U, V]] extends GenomicDataset[T, U, V] {

  /**
   * The processing steps that have been applied to this GenomicDataset.
   */
  val processingSteps: Seq[ProcessingStep]

  /**
   * Replaces the processing steps attached to this RDD.
   *
   * @param newProcessingSteps The new processing steps to attach to this RDD.
   * @return Returns a new GenomicDataset with new processing lineage attached.
   */
  def replaceProcessingSteps(newProcessingSteps: Seq[ProcessingStep]): V

  /**
   * Merges a new processing record with the extant computational lineage.
   *
   * @param newProcessingStep
   * @return Returns a new GenomicDataset with new record groups merged in.
   */
  def addProcessingStep(newProcessingStep: ProcessingStep): V = {
    replaceProcessingSteps(processingSteps :+ newProcessingStep)
  }
}

/**
 * An abstract class describing a GenomicDataset where:
 *
 * * The data are Avro IndexedRecords.
 * * The data are associated to record groups (i.e., they are reads or fragments).
 */
abstract class AvroRecordGroupGenomicDataset[T <% IndexedRecord: Manifest, U <: Product, V <: AvroRecordGroupGenomicDataset[T, U, V]] extends AvroGenomicDataset[T, U, V]
    with GenomicDatasetWithLineage[T, U, V] {

  override def toString = "%s with %d reference sequences, %d read groups, and %d processing steps"
    .format(getClass.getSimpleName, sequences.size, recordGroups.size, processingSteps.size)

  /**
   * A dictionary describing the record groups attached to this GenomicDataset.
   */
  val recordGroups: RecordGroupDictionary

  /**
   * Replaces the record groups attached to this RDD.
   *
   * @param newRecordGroups The new record group dictionary to attach.
   * @return Returns a new GenomicDataset with new record groups attached.
   */
  def replaceRecordGroups(newRecordGroups: RecordGroupDictionary): V

  /**
   * Merges a new set of record groups with the extant record groups.
   *
   * @param recordGroupsToAdd The record group dictionary to append to the
   *   extant record groups.
   * @return Returns a new GenomicDataset with new record groups merged in.
   */
  def addRecordGroups(recordGroupsToAdd: RecordGroupDictionary): V = {
    replaceRecordGroups(recordGroups ++ recordGroupsToAdd)
  }

  /**
   * Adds a single record group to the extant record groups.
   *
   * @param recordGroupToAdd The record group to append to the extant record
   *   groups.
   * @return Returns a new GenomicDataset with the new record group added.
   */
  def addRecordGroup(recordGroupToAdd: RecordGroup): V = {
    addRecordGroups(RecordGroupDictionary(Seq(recordGroupToAdd)))
  }

  /**
   * Save the record groups to disk.
   *
   * @param filePath The filepath to the file where we will save the record groups.
   */
  protected def saveRecordGroups(filePath: String): Unit = {

    // convert record group to avro and save
    val rgMetadata = recordGroups.recordGroups
      .map(_.toMetadata)

    saveAvro("%s/_rgdict.avro".format(filePath),
      rdd.context,
      RecordGroupMetadata.SCHEMA$,
      rgMetadata)
  }

  /**
   * Save the processing steps to disk.
   *
   * @param filePath The filepath to the directory within which we will save the
   *   processing step descriptions..
   */
  protected def saveProcessingSteps(filePath: String) {
    // save processing metadata
    saveAvro("%s/_processing.avro".format(filePath),
      rdd.context,
      ProcessingStep.SCHEMA$,
      processingSteps)
  }

  override protected def saveMetadata(filePath: String): Unit = {
    savePartitionMap(filePath)
    saveProcessingSteps(filePath)
    saveSequences(filePath)
    saveRecordGroups(filePath)
  }
}

private[rdd] trait VCFSupportingGenomicDataset[T, U <: Product, V <: VCFSupportingGenomicDataset[T, U, V]] extends GenomicDataset[T, U, V] {

  val headerLines: Seq[VCFHeaderLine]

  /**
   * Replaces the header lines attached to this RDD.
   *
   * @param newHeaderLines The new header lines to attach to this RDD.
   * @return A new RDD with the header lines replaced.
   */
  def replaceHeaderLines(newHeaderLines: Seq[VCFHeaderLine]): V

  /**
   * Appends new header lines to the existing lines.
   *
   * @param headerLinesToAdd Zero or more header lines to add.
   * @return A new RDD with the new header lines added.
   */
  def addHeaderLines(headerLinesToAdd: Seq[VCFHeaderLine]): V = {
    replaceHeaderLines(headerLines ++ headerLinesToAdd)
  }

  /**
   * Appends a new header line to the existing lines.
   *
   * @param headerLineToAdd A header line to add.
   * @return A new RDD with the new header line added.
   */
  def addHeaderLine(headerLineToAdd: VCFHeaderLine): V = {
    addHeaderLines(Seq(headerLineToAdd))
  }

  /**
   * Adds a VCF header line describing an array format field, with fixed count.
   *
   * @param id The identifier for the field.
   * @param count The number of elements in the array.
   * @param description A description of the data stored in this format field.
   * @param lineType The type of the data stored in this format field.
   * @return A new RDD with the new header line added.
   */
  def addFixedArrayFormatHeaderLine(id: String,
                                    count: Int,
                                    description: String,
                                    lineType: VCFHeaderLineType): V = {
    addHeaderLine(new VCFFormatHeaderLine(id, count, lineType, description))
  }

  /**
   * Adds a VCF header line describing an array format field, with fixed count.
   *
   * Java friendly variant.
   *
   * @param id The identifier for the field.
   * @param count The number of elements in the array.
   * @param description A description of the data stored in this format field.
   * @param lineType The type of the data stored in this format field.
   * @return A new RDD with the new header line added.
   */
  def addFixedArrayFormatHeaderLine(id: java.lang.String,
                                    count: java.lang.Integer,
                                    lineType: VCFHeaderLineType,
                                    description: java.lang.String): V = {
    addFixedArrayFormatHeaderLine(id, count, description, lineType)
  }

  /**
   * Adds a VCF header line describing a scalar format field.
   *
   * @param id The identifier for the field.
   * @param description A description of the data stored in this format field.
   * @param lineType The type of the data stored in this format field.
   * @return A new RDD with the new header line added.
   */
  def addScalarFormatHeaderLine(id: String,
                                description: String,
                                lineType: VCFHeaderLineType): V = {
    addFixedArrayFormatHeaderLine(id, 1, description, lineType)
  }

  // this is a private helper function, all user code should use one of the
  // add[a-zA-Z]+ArrayFormatHeaderLine functions
  private def addArrayFormatHeaderLine(id: String,
                                       description: String,
                                       count: VCFHeaderLineCount,
                                       lineType: VCFHeaderLineType): V = {
    addHeaderLine(new VCFFormatHeaderLine(id, count, lineType, description))
  }

  /**
   * Adds a VCF header line describing an 'G' array format field.
   *
   * This adds a format field that is an array whose length is equal to the
   * number of genotypes for the genotype we are annotating.
   *
   * @param id The identifier for the field.
   * @param description A description of the data stored in this format field.
   * @param lineType The type of the data stored in this format field.
   * @return A new RDD with the new header line added.
   */
  def addGenotypeArrayFormatHeaderLine(id: String,
                                       description: String,
                                       lineType: VCFHeaderLineType): V = {
    addArrayFormatHeaderLine(id,
      description,
      VCFHeaderLineCount.G,
      lineType)
  }

  /**
   * Adds a VCF header line describing an 'A' array format field.
   *
   * This adds a format field that is an array whose length is equal to the
   * number of alternate alleles for the genotype we are annotating.
   *
   * @param id The identifier for the field.
   * @param description A description of the data stored in this format field.
   * @param lineType The type of the data stored in this format field.
   * @return A new RDD with the new header line added.
   */
  def addAlternateAlleleArrayFormatHeaderLine(id: String,
                                              description: String,
                                              lineType: VCFHeaderLineType): V = {
    addArrayFormatHeaderLine(id,
      description,
      VCFHeaderLineCount.A,
      lineType)
  }

  /**
   * Adds a VCF header line describing an 'R' array format field.
   *
   * This adds a format field that is an array whose length is equal to the
   * total number of alleles (including the reference allele) for the genotype
   * we are annotating.
   *
   * @param id The identifier for the field.
   * @param description A description of the data stored in this format field.
   * @param lineType The type of the data stored in this format field.
   * @return A new RDD with the new header line added.
   */
  def addAllAlleleArrayFormatHeaderLine(id: String,
                                        description: String,
                                        lineType: VCFHeaderLineType): V = {
    addArrayFormatHeaderLine(id,
      description,
      VCFHeaderLineCount.R,
      lineType)
  }

  /**
   * Adds a VCF header line describing an array info field, with fixed count.
   *
   * @param id The identifier for the field.
   * @param count The number of elements in the array.
   * @param description A description of the data stored in this info field.
   * @param lineType The type of the data stored in this info field.
   * @return A new RDD with the new header line added.
   */
  def addFixedArrayInfoHeaderLine(id: String,
                                  count: Int,
                                  description: String,
                                  lineType: VCFHeaderLineType): V = {
    addHeaderLine(new VCFInfoHeaderLine(id, count, lineType, description))
  }

  /**
   * Adds a VCF header line describing an array info field, with fixed count.
   *
   * Java friendly variant.
   *
   * @param id The identifier for the field.
   * @param count The number of elements in the array.
   * @param description A description of the data stored in this info field.
   * @param lineType The type of the data stored in this info field.
   * @return A new RDD with the new header line added.
   */
  def addFixedArrayInfoHeaderLine(id: java.lang.String,
                                  count: java.lang.Integer,
                                  lineType: VCFHeaderLineType,
                                  description: java.lang.String): V = {
    addFixedArrayInfoHeaderLine(id, count, description, lineType)
  }

  /**
   * Adds a VCF header line describing a scalar info field.
   *
   * @param id The identifier for the field.
   * @param description A description of the data stored in this info field.
   * @param lineType The type of the data stored in this info field.
   * @return A new RDD with the new header line added.
   */
  def addScalarInfoHeaderLine(id: String,
                              description: String,
                              lineType: VCFHeaderLineType): V = {
    addFixedArrayInfoHeaderLine(id, 1, description, lineType)
  }

  // this is a private helper function, all user code should use one of the
  // add[a-zA-Z]+ArrayInfoHeaderLine functions
  private def addArrayInfoHeaderLine(id: String,
                                     description: String,
                                     count: VCFHeaderLineCount,
                                     lineType: VCFHeaderLineType): V = {
    addHeaderLine(new VCFInfoHeaderLine(id, count, lineType, description))
  }

  /**
   * Adds a VCF header line describing an 'A' array info field.
   *
   * This adds a info field that is an array whose length is equal to the
   * number of alternate alleles for the genotype we are annotating.
   *
   * @param id The identifier for the field.
   * @param description A description of the data stored in this info field.
   * @param lineType The type of the data stored in this info field.
   * @return A new RDD with the new header line added.
   */
  def addAlternateAlleleArrayInfoHeaderLine(id: String,
                                            description: String,
                                            lineType: VCFHeaderLineType): V = {
    addArrayInfoHeaderLine(id,
      description,
      VCFHeaderLineCount.A,
      lineType)
  }

  /**
   * Adds a VCF header line describing an 'R' array info field.
   *
   * This adds a info field that is an array whose length is equal to the
   * total number of alleles (including the reference allele) for the genotype
   * we are annotating.
   *
   * @param id The identifier for the field.
   * @param description A description of the data stored in this info field.
   * @param lineType The type of the data stored in this info field.
   * @return A new RDD with the new header line added.
   */
  def addAllAlleleArrayInfoHeaderLine(id: String,
                                      description: String,
                                      lineType: VCFHeaderLineType): V = {
    addArrayInfoHeaderLine(id,
      description,
      VCFHeaderLineCount.R,
      lineType)
  }

  /**
   * Adds a VCF header line describing a variant/genotype filter.
   *
   * @param id The identifier for the filter.
   * @param description A description of the filter.
   * @return A new RDD with the new header line added.
   */
  def addFilterHeaderLine(id: String,
                          description: String): V = {
    addHeaderLine(new VCFFilterHeaderLine(id, description))
  }
}

/**
 * An abstract class that extends the MultisampleGenomicDataset trait, where the data
 * are Avro IndexedRecords.
 */
abstract class MultisampleAvroGenomicDataset[T <% IndexedRecord: Manifest, U <: Product, V <: MultisampleAvroGenomicDataset[T, U, V]] extends AvroGenomicDataset[T, U, V]
    with MultisampleGenomicDataset[T, U, V] {

  override protected def saveMetadata(filePath: String): Unit = {
    savePartitionMap(filePath)
    saveSequences(filePath)
    saveSamples(filePath)
  }
}

/**
 * An abstract class that extends GenomicDataset and where the underlying data are
 * Avro IndexedRecords. This abstract class provides methods for saving to
 * Parquet, and provides hooks for writing the metadata.
 */
abstract class AvroGenomicDataset[T <% IndexedRecord: Manifest, U <: Product, V <: AvroGenomicDataset[T, U, V]] extends GenomicDataset[T, U, V] {

  protected def saveRddAsParquet(args: SaveArgs): Unit = {
    saveRddAsParquet(
      args.outputPath,
      args.blockSize,
      args.pageSize,
      args.compressionCodec,
      args.disableDictionaryEncoding
    )
  }

  /**
   * Saves an RDD of Avro data to Parquet.
   *
   * @param pathName The path to save the file to.
   * @param blockSize The size in bytes of blocks to write. Defaults to 128 * 1024 * 1024.
   * @param pageSize The size in bytes of pages to write. Defaults to 1 * 1024 * 1024.
   * @param compressCodec The compression codec to apply to pages. Defaults to CompressionCodecName.GZIP.
   * @param disableDictionaryEncoding If false, dictionary encoding is used. If
   *   true, delta encoding is used. Defaults to false.
   * @param optSchema The optional schema to set. Defaults to None.
   */
  protected def saveRddAsParquet(
    pathName: String,
    blockSize: Int = 128 * 1024 * 1024,
    pageSize: Int = 1 * 1024 * 1024,
    compressCodec: CompressionCodecName = CompressionCodecName.GZIP,
    disableDictionaryEncoding: Boolean = false,
    optSchema: Option[Schema] = None): Unit = SaveAsADAM.time {
    log.info("Saving data in ADAM format")

    val job = HadoopUtil.newJob(rdd.context)
    ParquetOutputFormat.setCompression(job, compressCodec)
    ParquetOutputFormat.setEnableDictionary(job, !disableDictionaryEncoding)
    ParquetOutputFormat.setBlockSize(job, blockSize)
    ParquetOutputFormat.setPageSize(job, pageSize)
    AvroParquetOutputFormat.setSchema(
      job,
      optSchema.getOrElse(manifest[T].runtimeClass.asInstanceOf[Class[T]].newInstance().getSchema)
    )

    // Add the Void Key
    val recordToSave = rdd.map(p => (null, p))
    // Save the values to the ADAM/Parquet file
    recordToSave.saveAsNewAPIHadoopFile(
      pathName,
      classOf[java.lang.Void], manifest[T].runtimeClass.asInstanceOf[Class[T]], classOf[InstrumentedADAMAvroParquetOutputFormat],
      ContextUtil.getConfiguration(job)
    )
  }

  /**
   * Save the partition map to disk. This is done by adding the partition
   * map to the schema.
   *
   * @param filePath The filepath where we will save the partition map.
   */
  protected def savePartitionMap(filePath: String): Unit = {
    if (isSorted) {
      // converting using json4s
      val jsonString = "partitionMap" -> optPartitionMap.get.toSeq.map(f =>
        if (f.isEmpty) {
          ("ReferenceRegion1" -> "None") ~ ("ReferenceRegion2" -> "None")
        } else {
          // we have to save the pair as ReferenceRegion1 and ReferenceRegion2 so we don't
          // lose either of them when they get converted to Maps
          ("ReferenceRegion1" -> (("referenceName" -> f.get._1.referenceName) ~
            ("start" -> f.get._1.start) ~ ("end" -> f.get._1.end))) ~
            ("ReferenceRegion2" -> (("referenceName" -> f.get._2.referenceName) ~
              ("start" -> f.get._2.start) ~ ("end" -> f.get._2.end)))
        })
      val schema = Contig.SCHEMA$
      schema.addProp("partitionMap", compact(render(jsonString)).asInstanceOf[Any])

      saveAvro("%s/_partitionMap.avro".format(filePath),
        rdd.context,
        schema,
        sequences.toAvro)
    }
  }

  /**
   * Called in saveAsParquet after saving RDD to Parquet to save metadata.
   *
   * Writes any necessary metadata to disk. If not overridden, writes the
   * sequence dictionary to disk as Avro.
   *
   * @param filePath The filepath to the file where we will save the Metadata.
   */
  protected def saveMetadata(filePath: String): Unit = {
    savePartitionMap(filePath)
    saveSequences(filePath)
  }

  /**
   * Saves this RDD to disk as a Parquet file.
   *
   * @param filePath Path to save the file at.
   * @param blockSize Size per block.
   * @param pageSize Size per page.
   * @param compressCodec Name of the compression codec to use.
   * @param disableDictionaryEncoding Whether or not to disable bit-packing.
   *   Default is false.
   */
  def saveAsParquet(
    filePath: String,
    blockSize: Int = 128 * 1024 * 1024,
    pageSize: Int = 1 * 1024 * 1024,
    compressCodec: CompressionCodecName = CompressionCodecName.GZIP,
    disableDictionaryEncoding: Boolean = false) {
    saveRddAsParquet(filePath,
      blockSize,
      pageSize,
      compressCodec,
      disableDictionaryEncoding)
    saveMetadata(filePath)
  }

  /**
   * Saves this RDD to disk as a Parquet file.
   *
   * @param filePath Path to save the file at.
   * @param blockSize Size per block.
   * @param pageSize Size per page.
   * @param compressCodec Name of the compression codec to use.
   * @param disableDictionaryEncoding Whether or not to disable bit-packing.
   */
  def saveAsParquet(
    filePath: java.lang.String,
    blockSize: java.lang.Integer,
    pageSize: java.lang.Integer,
    compressCodec: CompressionCodecName,
    disableDictionaryEncoding: java.lang.Boolean) {
    saveAsParquet(
      new JavaSaveArgs(filePath,
        blockSize = blockSize,
        pageSize = pageSize,
        compressionCodec = compressCodec,
        disableDictionaryEncoding = disableDictionaryEncoding))
  }

  /**
   * Saves this RDD to disk as a Parquet file.
   *
   * @param filePath Path to save the file at.
   */
  def saveAsParquet(filePath: java.lang.String) {
    saveAsParquet(new JavaSaveArgs(filePath))
  }

  /**
   * Save partition size into the partitioned Parquet flag file.
   *
   * @param filePath Path to save the file at.
   * @param partitionSize Partition bin size, in base pairs, used in Hive-style partitioning.
   *
   */
  private def writePartitionedParquetFlag(filePath: String, partitionSize: Int): Unit = {
    val path = new Path(filePath, "_partitionedByStartPos")
    val fs: FileSystem = path.getFileSystem(rdd.context.hadoopConfiguration)
    val f = fs.create(path)
    f.writeInt(partitionSize)
    f.close()
  }

  /**
   *  Saves this RDD to disk in range binned partitioned Parquet + Avro format
   *
   * @param filePath Path to save the file at.
   * @param compressCodec Name of the compression codec to use.
   * @param partitionSize size of partitions used when writing parquet, in base pairs.  Defaults to 1000000.
   */
  def saveAsPartitionedParquet(filePath: String,
                               compressCodec: CompressionCodecName = CompressionCodecName.GZIP,
                               partitionSize: Int = 1000000) {
    log.warn("Saving directly as Hive-partitioned Parquet from SQL. " +
      "Options other than compression codec are ignored.")
    val df = toDF()
    df.withColumn("positionBin", floor(df("start") / partitionSize))
      .write
      .partitionBy("contigName", "positionBin")
      .format("parquet")
      .option("spark.sql.parquet.compression.codec", compressCodec.toString.toLowerCase())
      .save(filePath)
    writePartitionedParquetFlag(filePath, partitionSize)
    saveMetadata(filePath)
  }

}

private[rdd] class InstrumentedADAMAvroParquetOutputFormat extends InstrumentedOutputFormat[Void, IndexedRecord] {
  override def outputFormatClass(): Class[_ <: NewOutputFormat[Void, IndexedRecord]] = classOf[AvroParquetOutputFormat[IndexedRecord]]
  override def timerName(): String = WriteADAMRecord.timerName
}

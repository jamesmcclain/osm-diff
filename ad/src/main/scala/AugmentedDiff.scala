package osmdiff

import org.apache.log4j.{Level, Logger}
import org.apache.spark._
import org.apache.spark.rdd._
import org.apache.spark.sql._
import org.apache.spark.sql.expressions.Window
import org.apache.spark.sql.functions._

import org.openstreetmap.osmosis.xml.common.CompressionMethod
import org.openstreetmap.osmosis.xml.v0_6.XmlChangeReader

import scala.collection.mutable

import java.io.File


object AugmentedDiff {

  val spark = Common.sparkSession("Augmented Diff")
  import spark.implicits._

  val window1 = Window.partitionBy("from_id", "from_type").orderBy(desc("instant"))
  val window2 = Window.partitionBy("id", "type").orderBy(desc("timestamp"))

  def augment(rows: DataFrame) = {
    val touched = spark.table("index").union(spark.table("index_updates"))
      .join(
        rows,
        (col("from_id") === col("id")) && (col("from_type") === col("type")),
        "left_semi")
      .withColumn("row_number", row_number().over(window1))
      .filter(col("row_number") === 1)
      .select(col("to_id"), col("to_type"), col("instant"))
      .distinct

    spark.table("osm").union(spark.table("osm_updates"))
      .join(
        touched,
        ((col("id") === col("to_id")) &&
         (col("type") === col("to_type")) &&
         (Common.getInstant(col("timestamp")) < col("instant"))),
        "left_semi")
      .withColumn("row_number", row_number().over(window2))
      .filter(col("row_number") === 1)
      .drop("row_number")
      .count
  }

  def main(args: Array[String]): Unit = {

    Logger.getLogger("org").setLevel(Level.ERROR)
    Logger.getLogger("akka").setLevel(Level.ERROR)

    if (args(0) != "xxx") {
      val cr = new XmlChangeReader(new File(args(0)), true, CompressionMethod.None)
      val ca = new ChangeAugmenter(spark)
      cr.setChangeSink(ca)
      cr.run
    }
    else {
      val sampleRate =
        if (args.length > 1) args(1).toDouble
        else 0.05
      val updates = spark.table("osm_updates").sample(sampleRate)
      println(s"updates: ${updates.count}")

      val time1 = System.currentTimeMillis
      println(s"size: ${augment(updates)}")
      val time2 = System.currentTimeMillis
      println(s"size: ${augment(updates)}")
      val time3 = System.currentTimeMillis
      println(s"times: ${time2 - time1} ${time3 - time2}")
    }
  }

}

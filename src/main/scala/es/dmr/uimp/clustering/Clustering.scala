package es.dmr.uimp.clustering

import java.io.{BufferedWriter, File, FileWriter}

import org.apache.commons.lang3.StringUtils
import org.apache.spark.SparkContext
import org.apache.spark.mllib.linalg.{Vector, Vectors}
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.{DataFrame, SQLContext}
import org.apache.spark.sql.functions._

import scala.collection.mutable.ArrayBuffer
import org.apache.spark.mllib.feature.StandardScaler
import org.apache.spark.ml.feature.VectorAssembler
import org.apache.spark.ml.feature.MinMaxScaler
import org.apache.spark.ml.Pipeline
import org.apache.spark.sql.functions.udf
import org.apache.spark.sql.types.DoubleType
import org.apache.spark.sql.Row
import java.nio.file.Files
import java.nio.file.Paths

/**
  * Created by root on 3/12/17.
  */
object Clustering {
  /**
    * Load data from file, parse the data and normalize the data.
    */
  def loadData(sc: SparkContext, file : String) : DataFrame = {

    val sqlContext = new SQLContext(sc)

    // Function to extract the hour from the date string
    val gethour =  udf[Double, String]((date : String) => {
      var out = -1.0
      if (!StringUtils.isEmpty(date)) {
        val hour = date.substring(10).split(":")(0)
        if (!StringUtils.isEmpty(hour))
          out = hour.trim.toDouble
      }
      out
    })
    // Load the csv data
    val df = sqlContext.read
      .format("com.databricks.spark.csv")
      .option("header", "true") // Use first line of all files as header
      .option("inferSchema", "true") // Automatically infer data types
      .load(file)
      .withColumn("Hour", gethour(col("InvoiceDate")))

    df
  }
  

  def featurizeData(df : DataFrame) : DataFrame = {
    val featurized_df = df.groupBy("InvoiceNo").agg(
        avg("UnitPrice_scaled").alias("AvgUnitPrice"),
        min("UnitPrice_scaled").alias("MinUnitPrice"),
        max("UnitPrice_scaled").alias("MaxUnitPrice"),
        last("Hour").alias("Time"),
        sum("Quantity_scaled").alias("NumberItems")
    )
    featurized_df
  }
  

  
  def extactValue(ls:Vector, position: Int) {
     ls.toArray
  } 
  
   def scaleData(df : DataFrame, model : String) : DataFrame = {
    val columns = Array("UnitPrice","Quantity")

    var df2 = df
    
    // Calculate min and max UnitPrice values
    val (pMin, pMax) = df.agg(min(col("UnitPrice")), max(col("UnitPrice"))).first match {
        case Row(x: Double, y: Double) => (x, y)
     }  
    
    // Calculate min and max Quantity values
    val (qMin, qMax) = df.agg(min(col("Quantity")), max(col("Quantity"))).first match {
        case Row(x: Int, y: Int) => (x, y)
    }
    
    saveScaleFactors(pMin.toString, pMax.toString, "UnitPrice")
    saveScaleFactors(qMin.toString, qMax.toString, "Quantity")
    
    println("Scaling....")
    
    // Scale values
    val pNormalized = (col("UnitPrice") - pMin) / (pMax - pMin)
    val qNormalized = (col("Quantity") - qMin) / (qMax - qMin)

    df2 = df2.withColumn("UnitPrice_scaled", pNormalized)
    df2 = df2.withColumn("Quantity_scaled", qNormalized)
    
    df2.drop("UnitPrice", "Quantity")
    
    df2
  }
   
   def saveScaleFactors(min:String, max:String, column: String){
     val path = "./"+column+"_Scale"
      if(!Files.exists(Paths.get(path))){
        val file = new File(path)
        val bw = new BufferedWriter(new FileWriter(file))
        bw.write(min+", "+ max)
        bw.close()
      }
   }

  def filterData(df : DataFrame) : DataFrame = {
    val filtered_df = df.filter(not(substring(column("InvoiceNo"), 0, 1).equalTo("C"))
        .and(column("AvgUnitPrice").isNotNull)
        .and(column("MinUnitPrice").isNotNull)
        .and(column("MaxUnitPrice").isNotNull)
        .and(column("Time").isNotNull)
        .and(column("NumberItems").isNotNull)
        )
    filtered_df
  }

  def toDataset(df: DataFrame): RDD[Vector] = {
    val data = df.select("AvgUnitPrice", "MinUnitPrice", "MaxUnitPrice", "Time", "NumberItems").rdd
      .map(row =>{
        val buffer = ArrayBuffer[Double]()
        buffer.append(row.getAs("AvgUnitPrice"))
        buffer.append(row.getAs("MinUnitPrice"))
        buffer.append(row.getAs("MaxUnitPrice"))
        buffer.append(row.getAs("Time"))
        buffer.append(row.getAs("NumberItems"))
        val vector = Vectors.dense(buffer.toArray)
        vector
      })

    data
  }

  def elbowSelection(costs: Seq[Double], ratio : Double): Int = {
    var lastCost:Double = 1
    var selectedK:Int = -1
    
    for((cost, k) <- costs.zipWithIndex){
      if((cost/lastCost) > ratio){
        selectedK = k
      }
      lastCost = cost
    }
    selectedK
  }

  def saveThreshold(threshold : Double, fileName : String) = {
    val file = new File(fileName)
    val bw = new BufferedWriter(new FileWriter(file))
    // decide threshold for anomalies
    bw.write(threshold.toString) // last item is the threshold
    bw.close()
  }

}

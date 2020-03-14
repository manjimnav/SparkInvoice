package es.dmr.uimp.realtime

import org.apache.commons.lang3.StringUtils
import org.apache.spark.SparkConf
import org.apache.spark.SparkContext
import org.apache.spark.mllib.clustering._
import org.apache.spark.streaming._
import org.apache.spark.streaming.dstream.DStream
import org.apache.spark.streaming.kafka.KafkaUtils
import org.apache.kafka.clients.producer.{ KafkaProducer, ProducerConfig, ProducerRecord }
import java.util.HashMap

import com.univocity.parsers.csv.{ CsvParser, CsvParserSettings }
import es.dmr.uimp.clustering.KMeansClusterInvoices
import org.apache.spark.broadcast.Broadcast
import org.apache.spark.mllib.linalg.{ Vector, Vectors }
import org.apache.spark.rdd.RDD
import scala.util.Try

object InvoicePipeline {

  case class Purchase(invoiceNo: String, quantity: Int, invoiceDate: String,
                      unitPrice: Double, customerID: String, country: String)

  case class Invoice(invoiceNo: String, avgUnitPrice: Double,
                     minUnitPrice: Double, maxUnitPrice: Double, time: Double,
                     numberItems: Double, lastUpdated: Long, lines: Int, customerId: String)

  def main(args: Array[String]) {

    val Array(modelFile, thresholdFile, modelFileBisect, thresholdFileBisect, zkQuorum, group, topics, numThreads, brokers) = args
    val sparkConf = new SparkConf().setAppName("InvoicePipeline")
    val sc = new SparkContext(sparkConf)
    val ssc = new StreamingContext(sc, Seconds(20))
    sc.setLogLevel("ERROR")

    // Checkpointing
    ssc.checkpoint("./checkpoint")

    /**
     * Load required variables
     */
    val Tuple2(kmeans, kmeansThr) = loadKMeansAndThreshold(sc, modelFile, thresholdFile)
    val Tuple2(bikmeans, bikmeansThr) = loadBisectKMeansAndThreshold(sc, modelFileBisect, thresholdFileBisect)
    val Tuple2(priceParams, quantityParams) = loadScaleParams(sc)

    /**
     * Broadcast variables
     */
    val brokersBC = sc.broadcast(brokers)

    val kmeansBC = sc.broadcast(kmeans)
    val kmeansThrBC = sc.broadcast(kmeansThr)

    val priceParamsBC = sc.broadcast(priceParams)
    val quantityParamsBC = sc.broadcast(quantityParams)

    val bikmeansBC = sc.broadcast(bikmeans)
    val bikmeansThrBC = sc.broadcast(bikmeansThr)

    /**
     * Pipeline
     */

    // connect to kafka
    val purchasesFeed = connectToPurchases(ssc, zkQuorum, group, topics, numThreads)

    // Completed invoices stream
    val completedInvoices = purchasesFeed.filter(purchase => !isInvalid(purchase._2)) // Filter not valid entries
      .map(purchase => (purchase._1, purchase._2.split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)"))) // Construct (invoiceId, [inputs]) tuple
      .map(purchaseValues => (purchaseValues._1, Array[Double](
        scaleData(purchaseValues._2(3).toDouble, priceParamsBC.value),
        getHour(purchaseValues._2(4)), scaleData(purchaseValues._2(5).toDouble, priceParamsBC.value)))) // Construct scaled state input
      .mapWithState(StateSpec.function(mappingFunc _).timeout(Seconds(40))) // Construct model input
      .filter(modelInvoiceInput => modelInvoiceInput._2 != null) //Filter completed invoices

    // Filter canceled invoices
    completedInvoices.filter(completedInvoiceInput => completedInvoiceInput._1.matches("^C\\d+$")) 
      .countByWindow(Minutes(8), Seconds(20)) // Count cancelled invoices in window of 8 minutes in intervals of 20 seconds
      .map(camceledInvoicesCount => ("", camceledInvoicesCount.toString)).foreachRDD(rdd => {
        publishToKafka("cancelaciones")(brokersBC)(rdd) // Publish to topic cancelaciones
      })

    val notCanceledInvoices = completedInvoices.filter(completedInvoiceInput => !(completedInvoiceInput._1.matches("^C\\d+$"))) // Filter not canceled invoices
    
    // Kmeans anomalies
    notCanceledInvoices.map(notCanceledcompletedInvoiceInput => (notCanceledcompletedInvoiceInput._1, Vectors.sqdist(
      notCanceledcompletedInvoiceInput._2,
      kmeansBC.value.clusterCenters(kmeansBC.value.predict(notCanceledcompletedInvoiceInput._2))).toString)) // Calculate distance
      .filter(invoiceDistance => invoiceDistance._2.toDouble >= kmeansThrBC.value) // Filter anomalies
      .map(anomaly => (anomaly._1, anomaly._1 + "->" + anomaly._2))
      .foreachRDD(rdd => {
        publishToKafka("anomalias_kmeans")(brokersBC)(rdd) // Publish to kmeans anomalies topic
      })
    
    // BisectKmeans anomalies
    notCanceledInvoices.map(notCanceledcompletedInvoiceInput => (notCanceledcompletedInvoiceInput._1,
        Vectors.sqdist(notCanceledcompletedInvoiceInput._2, 
            bikmeansBC.value.clusterCenters(bikmeansBC.value.predict(notCanceledcompletedInvoiceInput._2))).toString)) // Create tuple (invoiceId, distance)
      .filter(invoiceDistance => invoiceDistance._2.toDouble >= bikmeansThrBC.value) // Filter anomalies
      .map(anomaly => (anomaly._1, anomaly._1 + "->" + anomaly._2)).foreachRDD(rdd => {
        publishToKafka("anomalias_bisect_kmeans")(brokersBC)(rdd) // Publish to bisect kmeans anomalies topic
      })

    // Filter invalid purchases
    purchasesFeed.filter(entry => isInvalid(entry._2)) 
    .foreachRDD(rdd => {
      publishToKafka("facturas_erroneas")(brokersBC)(rdd) // Publish to facturaserroneas topic
    })

    ssc.start() // Start the computation
    ssc.awaitTermination()
  }
  /**
   * This method calculate the features of the model online instead of save all the products in one array and later calculate. The method only will return the features
   * when the invoice timed out.
   */	
  def mappingFunc(invoiceId: String, one: Option[Array[Double]], state: State[Array[Double]]): (String, Vector) = {
	
    if (state.isTimingOut()) {
      return (invoiceId, Vectors.dense(state.get.init)) // After 40secs the state will timeout and will be returned
    } else {
      //Avg, Min,                     Max,                     Hour, Number products,     Number invoices
      var oldState = state.getOption().getOrElse(Array[Double](0.0, Double.PositiveInfinity, Double.NegativeInfinity, 0.0, 0.0, 0.0))
      val numberLinesInvoice = oldState(5) + 1 // Add one to number of lines received from invoice
      val linePrice = one.get(2) // Obtain the price

      val incrementalAverage = oldState(0) + (linePrice + oldState(0)) / numberLinesInvoice // Calculate the incremental average
      val min = math.min(oldState(1), linePrice) // Update the minimum value
      val max = math.max(oldState(2), linePrice) // Update the maximum value
      val hour = one.get(1) // Get the hour (Note only the last invoice line hour will be considered)
      val nProducts = oldState(4) + one.get(0) // Increment the number of products

      state.update(Array[Double](incrementalAverage, min, max, hour, nProducts, numberLinesInvoice))
    }

    return (invoiceId, null) // As we don't know if the invoice is completed return null
  }

  def isInvalid(entry: String): Boolean = {
    val values = entry.split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)")

    if (values.length != 8) {
      return true
    }

    for (x <- values) {
      if (x == null || x.equals("")) {
        return true
      }
    }

    if (!values(0).matches("^C?\\d{1,}$")) {
      return true
    }

    if (!values(1).matches("^\\d{1,}([A-Za-z]{1})?$")) {
      return true
    }

    if (!Try(values(3).toDouble).isSuccess) {
      return true
    }

    if (values(4).matches("^(0?[1-9]|[12][0-9]|3[01])-(0?[1-9]|1[0-2])-\\d{4} (00|[0-9]|1[0-9]|2[0-3]):([0-9]|[0-5][0-9])$")) {
      return true
    }

    if (!values(5).matches("^\\d+(\\.\\d{1,})?$") || values(5).toDouble <= 0) {
      return true
    }

    if (!Try(values(3).toInt).isSuccess) {
      return true
    }

    return false
  }

  def scaleData(value: Double, params: Tuple2[Double, Double]): Double = {
    val min = params._1
    val max = params._2
    (value - min) / (max - min)
  }

  def getHour(value: String): Double = {
    value.split(":")(0).split(" ")(1).toDouble
  }

  def mapToInt(entry: Tuple2[String, Vector]): Int = {
    if (entry._2 != null && entry._1.matches("^C\\d+$")) {
      return 1
    } else {
      return 0
    }

  }

  def publishToKafka(topic: String)(kafkaBrokers: Broadcast[String])(rdd: RDD[(String, String)]) = {
    rdd.foreachPartition(partition => {
      val producer = new KafkaProducer[String, String](kafkaConf(kafkaBrokers.value))
      partition.foreach(record => {
        producer.send(new ProducerRecord[String, String](topic, record._1, record._2.toString))
      })
      producer.close()
    })
  }

  def kafkaConf(brokers: String) = {
    val props = new HashMap[String, Object]()
    props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, brokers)
    props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringSerializer")
    props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringSerializer")
    props
  }

  /**
   * Load the model information: centroid and threshold
   */
  def loadKMeansAndThreshold(sc: SparkContext, modelFile: String, thresholdFile: String): Tuple2[KMeansModel, Double] = {
    val kmeans = KMeansModel.load(sc, modelFile)
    // parse threshold file
    val rawData = sc.textFile(thresholdFile, 20)
    val threshold = rawData.map { line => line.toDouble }.first()

    (kmeans, threshold)
  }

  def loadScaleParams(sc: SparkContext): Tuple2[Tuple2[Double, Double], Tuple2[Double, Double]] = {
    val rawDataParamsPrice = sc.textFile("./UnitPrice_Scale", 20)
    val rawDataParamsQuantity = sc.textFile("./Quantity_Scale", 20)
    val paramsPrice = rawDataParamsPrice.map { line => line.split(",") }.map(line => Tuple2(line(0).toDouble, line(1).toDouble)).first()
    val paramsQuantity = rawDataParamsQuantity.map { line => line.split(",") }.map(line => Tuple2(line(0).toDouble, line(1).toDouble)).first()

    (paramsPrice, paramsQuantity)
  }

  def loadBisectKMeansAndThreshold(sc: SparkContext, modelFile: String, thresholdFile: String): Tuple2[BisectingKMeansModel, Double] = {
    val kmeans = BisectingKMeansModel.load(sc, modelFile)
    // parse threshold file
    val rawData = sc.textFile(thresholdFile, 20)
    val threshold = rawData.map { line => line.toDouble }.first()

    val rawDataParamsPrice = sc.textFile("./KMeans_UnitPrice_Scale", 20)
    val rawDataParamsQuantity = sc.textFile("./KMeans_Quantity_Scale", 20)

    (kmeans, threshold)
  }

  def connectToPurchases(ssc: StreamingContext, zkQuorum: String, group: String,
                         topics: String, numThreads: String): DStream[(String, String)] = {

    ssc.checkpoint("checkpoint")
    val topicMap = topics.split(",").map((_, numThreads.toInt)).toMap
    KafkaUtils.createStream(ssc, zkQuorum, group, topicMap)
  }

}

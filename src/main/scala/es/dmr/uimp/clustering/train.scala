package es.dmr.uimp.clustering

import es.dmr.uimp.clustering.Clustering.elbowSelection
import org.apache.spark.mllib.clustering._
import org.apache.spark.mllib.linalg.{Vector, Vectors}
import org.apache.spark.rdd.RDD
import org.apache.spark.{SparkConf, SparkContext}

object KMeansClusterInvoices {

  def main(args: Array[String]) {

    import Clustering._

    val sparkConf = new SparkConf().setAppName("ClusterInvoices")
    val sc = new SparkContext(sparkConf)
    sc.setLogLevel("ERROR")
    
    // load data
    val df = loadData(sc, args(0))
    // Scale data
    val scaled = scaleData(df, args(3))
    
   // Very simple feature extraction from an invoice
    val featurized = featurizeData(scaled)

    // Filter not valid entries
    val filtered = filterData(featurized)
    
    // Transform in a dataset for MLlib
    val dataset = toDataset(filtered)

    // We are going to use this a lot (cache it)
    dataset.cache()

    // Print a sampl
    dataset.take(5).foreach(println)
    
    if(args(3)=="KMeans"){ // KMeans training
      println("Training KMeans....")
      val model = trainKMeansModel(dataset)
      // Save model
      println("Saving "+args(3)+" Model......")
      model.save(sc, args(1))
  
      // Save threshold
      val distances = dataset.map(d => distToCentroidKMeans(d, model))
      val threshold = distances.top(2000).last // set the last of the furthest 2000 data points as the threshold
      println("Saving "+args(3)+" threshold......")
      saveThreshold(threshold, args(2))
      
    }else{ // Bisect kmeans training
      println("Training BisectKMeans....")
      val model = trainBiKmeansModel(dataset)
      // Save model
      println("Saving "+args(3)+" Model......")
      model.save(sc, args(1))
  
      // Save threshold
      val distances = dataset.map(d => distToCentroidBiKmeans(d, model))
      val threshold = distances.top(2000).last // set the last of the furthest 2000 data points as the threshold
      println("Saving "+args(3)+" threshold......")
      saveThreshold(threshold, args(2))
    }
   
  }

  /**
   * Train a KMean model using invoice data.
   */
  def trainKMeansModel(data: RDD[Vector]): KMeansModel = {

    val models = 1 to 20 map { k =>
      val kmeans = new KMeans()
      kmeans.setK(k) // find that one center
      kmeans.run(data)
    }

    val costs = models.map(model => model.computeCost(data))

    val selected = elbowSelection(costs, 0.7)
    System.out.println("Selecting model: " + models(selected).k)
    models(selected)
  }
  
    /**
   * Train a BisectKMean model using invoice data.
   */
  def trainBiKmeansModel(data: RDD[Vector]): BisectingKMeansModel = {

    val models = 1 to 20 map { k =>
      val kmeans = new BisectingKMeans()
      kmeans.setK(k) // find that one center
      kmeans.run(data)
    }

    val costs = models.map(model => model.computeCost(data))

    val selected = elbowSelection(costs, 0.7)
    System.out.println("Selecting model: " + models(selected).k)
    models(selected)
  }

  /**
   * Calculate distance between data point to centroid from KMeans model.
   */
  def distToCentroidKMeans(datum: Vector, model: KMeansModel) : Double = {
    val centroid = model.clusterCenters(model.predict(datum)) // if more than 1 center
    Vectors.sqdist(datum, centroid)
  }
  
  /**
   * Calculate distance between data point to centroid from Bisect KMeans model.
   */
  def distToCentroidBiKmeans(datum: Vector, model: BisectingKMeansModel) : Double = {
    val centroid = model.clusterCenters(model.predict(datum)) // if more than 1 center
    Vectors.sqdist(datum, centroid)
  }

}


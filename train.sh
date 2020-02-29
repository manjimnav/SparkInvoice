#!/bin/bash

FILE=./src/main/resources/training.csv

if [ $1 = "KMeans" ];
then
	echo "Training KMeans...."
	spark-submit --class es.dmr.uimp.clustering.KMeansClusterInvoices --master local[4] target/scala-2.11/anomalyDetection-assembly-1.0.jar ${FILE} ./clustering ./threshold $1
else
	echo "Training BisectKMeans...."
	spark-submit --class es.dmr.uimp.clustering.KMeansClusterInvoices --master local[4] target/scala-2.11/anomalyDetection-assembly-1.0.jar ${FILE} ./clustering_bisect ./threshold_bisect $1
fi

exit 0
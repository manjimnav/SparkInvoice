El proyecto posee una serie de ejecutables sh con el que se pueden realizar 
las distintas acciones. 

Antes de ejecutar nada debe asegurarse detener instalado correctamente Apache Spark y disponible en las variables de entorno la carpeta bin.
Apache Kafka debe estar instalado correctamente también con el broker en el puerto 9092 y con los siguientes topics: cancelaciones, anomalias_kmeans, anomalias_bisect_kmeans, facturas_errores y purchases.

Los scripts y sus características son:

- train.sh: Este script solo precisa de un parámetro que puede ser "KMeans" or "BisectKMeans". Este script realiza el entrenamiento del modelo KMeans o BisectKMeans dependiendo del parámetro dando como salida el modelo, el umbral y los parámetros de escalado para el precio y la cantidad de las lineas que serán posteriormente usadas en el flujo principal.

- productiondata.sh: Este script no precisa de ningún parámetro e inicia el stream de lineas de facturas publicándolo en el topic "purchases".

- start_pipeline.sh: Inicia el pipeline y consumiendo los datos de purchases y publicando los resultados en los topics resultado.

name := "art-mosaic"
scalaVersion := "2.12.8"

libraryDependencies ++= Seq(
  "com.sksamuel.scrimage" %% "scrimage-core" % "2.1.8",
  "com.softwaremill.sttp" %% "core" % "1.5.4",
  "com.softwaremill.sttp" %% "okhttp-backend-monix" % "1.5.4",
  "com.softwaremill.sttp" %% "circe" % "1.5.6"
)

val circeVersion = "0.10.0"
libraryDependencies ++= Seq(
  "io.circe" %% "circe-core",
  "io.circe" %% "circe-generic",
  "io.circe" %% "circe-parser"
).map(_ % circeVersion)

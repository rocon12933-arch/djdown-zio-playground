ThisBuild / scalaVersion     := "3.3.6"
ThisBuild / version          := "0.0.2"
ThisBuild / organization     := "moe.karla"



val zioVersion = "2.1.19"
val quillVersion = "4.8.6"
val zioHttpVersion = "3.3.3"
val zioConfigVersion = "4.0.4"
val zioLoggingVersion = "2.5.0"


assembly / assemblyJarName := "hdjdown-zio.jar"

enablePlugins(JavaAppPackaging)

//addDependencyTreePlugin


ThisBuild / assemblyMergeStrategy := {
  case PathList(ps @ _*) if ps.last == "module-info.class" => MergeStrategy.discard
  case PathList(ps @ _*) if ps.contains("getquill")  => MergeStrategy.preferProject
  case PathList(ps @ _*) if ps.last == "io.netty.versions.properties"  => MergeStrategy.first
  case x =>
    val oldStrategy = (ThisBuild / assemblyMergeStrategy).value
    oldStrategy(x)
}


lazy val root = (project in file("."))
  .settings(
    name := "hdjdown-zio",
    scalaVersion := "3.3.6",
    scalacOptions ++= Seq("-Ykind-projector:underscores", "-language:postfixOps", "-deprecation"),
    javacOptions ++= Seq("-source", "21", "-target", "21"),
    libraryDependencies ++= Seq(

      "dev.zio" %% "zio" % zioVersion,
      "dev.zio" %% "zio-streams" % zioVersion,

      //"io.getquill" %% "quill-jdbc" % quillVersion,
      "io.getquill" %% "quill-jdbc-zio" % quillVersion,
      

      "dev.zio" %% "zio-http" % zioHttpVersion,

      "dev.zio" %% "zio-config" % zioConfigVersion,
      "dev.zio" %% "zio-config-typesafe" % zioConfigVersion,
      "dev.zio" %% "zio-config-magnolia" % zioConfigVersion,
      "dev.zio" %% "zio-config-refined"% zioConfigVersion,

      "dev.zio" %% "zio-logging" % zioLoggingVersion,
      //"dev.zio" %% "zio-logging-slf4j2" % zioLoggingVersion,

      "dev.zio" %% "zio-json" % "0.7.39",
      
      "com.zaxxer" % "HikariCP" % "6.2.1",
      //"org.xerial" % "sqlite-jdbc" % "3.49.1.0",
      //"com.h2database" % "h2" % "2.3.232",
      "com.codenameone" % "sqlite-jdbc" % "7.0.178",
      "org.flywaydb" % "flyway-core" % "10.22.0",
      "org.apache.commons" % "commons-compress" % "1.27.1",
      //"com.github.junrar" % "junrar" % "7.5.5",
      //"com.microsoft.playwright" % "playwright" % "1.51.0",
      "org.slf4j" % "slf4j-simple" % "2.0.17",
      "org.jsoup" % "jsoup" % "1.19.1",
      //"org.reflections" % "reflections" % "0.10.2"
    ),
    assembly / mainClass := Some("moe.karla.AppMain")
  )

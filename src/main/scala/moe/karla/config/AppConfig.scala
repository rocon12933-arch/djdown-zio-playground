package moe.karla.config

import moe.karla.config.*

import zio.*
import zio.config.*
import zio.config.typesafe.*
import zio.config.magnolia.*
import zio.config.refined.*


import eu.timepit.refined.*
import eu.timepit.refined.api.Refined
import eu.timepit.refined.numeric.*


final case class AppConfig(

  host: String,

  port: Int Refined GreaterEqual[1024],

  downPath: String,

  cachePath: String,

  maxWaiting: Int Refined GreaterEqual[1],
)



object AppConfig:

  def load = 
    ConfigProvider.fromHoconFile(new java.io.File("application.conf"))
      .load(deriveConfig[AppConfig].mapKey(toKebabCase))
      .tapError(e => ZIO.logError(e.getMessage()))
  
  val layer = ZLayer.fromZIO(load)




package moe.karla.handler.default


import zio.*

import zio.config.*
import zio.config.typesafe.*
import zio.config.magnolia.*
import zio.config.refined.*


final case class CommonConfig(
  defaultUserAgent: String,
)


object CommonConfig:

  def load = 
    ConfigProvider.fromHoconFile(new java.io.File("config/common.conf"))
    .load(deriveConfig[CommonConfig].mapKey(toKebabCase))
    .tapError(e => ZIO.logError(e.getMessage()))
    .orDie

  val layer = ZLayer.fromZIO(load)

package moe.karla.config


import zio.*
import zio.http.*
import zio.http.netty.client.NettyClientDriver


object ClientConfig:

  val layer = 
    ZLayer {
      for 
        config <- ZIO.service[AppConfig]
      yield ZClient.Config.default.ssl(ClientSSLConfig.Default).disabledConnectionPool.requestDecompression(true)
    } 
    ++ NettyClientDriver.live

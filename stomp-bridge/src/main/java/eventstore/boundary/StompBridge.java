package eventstore.boundary;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.core.shareddata.AsyncMap;
import io.vertx.core.shareddata.SharedData;
import io.vertx.ext.stomp.StompServer;
import io.vertx.ext.stomp.StompServerHandler;

import java.io.UnsupportedEncodingException;
import java.net.Inet4Address;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.util.LinkedHashMap;
import java.util.Map;


public class StompBridge extends AbstractVerticle {
  private Logger logger;

  @Override
  public void start() throws Exception {
    logger = LoggerFactory.getLogger(String.format("%s_%s", getClass(), deploymentID()));
    final Integer localPort = config().getInteger("stomp.port", 8091);
    final String hostAddress;
    final StompServerHandler stompServerHandler = StompServerHandler.create(vertx);

    if (config().getString("stomp.address", null) == null) {
      if (vertx.isClustered()) {
        hostAddress = Inet4Address.getLocalHost().getHostAddress();
        putAddressToSharedData(hostAddress);
      }
      else {
        hostAddress = "0.0.0.0";
      }
    }
    else {
      hostAddress = config().getString("stomp.address");
    }

    PushApi pushApi = PushApi.createProxy(vertx, "push-api");

    final StompServer stompServer = StompServer
        .create(vertx)
        .handler(
            stompServerHandler
                .closeHandler(stompServerConnection -> pushApi.unsubscribe(stompServerConnection.session()))
                .subscribeHandler(serverFrame -> {
                  String destination = serverFrame.frame().getDestination();
                  try {
                    final URI uri = new URI(destination.trim());
                    final JsonObject query = uri.toString().contains("?")
                                             ? new JsonObject(splitQuery(uri))
                                             : new JsonObject();
                    final String[] split = uri.getPath().split("/");

                    if (split.length != 3) {
                      throw new URISyntaxException(uri.getPath(), "no stream specified");
                    }

                    query.put("streamName", split[2]);
                    query.put("address", destination.trim());
                    query.put("clientId", serverFrame.connection().session());

                    logger.debug(String.format("subscribing: %s", query.encodePrettily()));
                    pushApi.subscribe(serverFrame.connection().session(), destination.trim());
                    System.out.println("subscribe");
                    stompServerHandler
                        .getOrCreateDestination(destination.trim())
                        .subscribe(serverFrame.connection(),
                                   serverFrame.frame());
                  }
                  catch (UnsupportedEncodingException |
                      URISyntaxException e) {
                    logger.error("invalid URI format", e);
                  }
                })
                )
        .listen(localPort, hostAddress);
    logger.info(String.format("STOMP listening on %s:%d", hostAddress, stompServer.actualPort()));
  }

  private void putAddressToSharedData(String hostAddress) {
    final SharedData sd = vertx.sharedData();

    sd.<String, String>getClusterWideMap("eventstore-config", res -> {
      if (res.succeeded()) {
        final AsyncMap<String, String> map = res.result();
        map.put("stomp-bridge-address", hostAddress, resPut -> {
          if (resPut.succeeded()) {
            logger.info(String.format("putted address %s", hostAddress));
          }
          else {
            logger.error("failed setting bridge address");
          }
        });
      }
      else {
        logger.error("failed setting bridge address");
      }
    });
  }

  private static Map<String, Object> splitQuery(final URI url) throws UnsupportedEncodingException {
    final Map<String, Object> query_pairs = new LinkedHashMap<>();
    final String query = url.getQuery();
    final String[] pairs = query.split("&");
    for (final String pair : pairs) {
      final int idx = pair.indexOf("=");
      query_pairs.put(URLDecoder.decode(pair.substring(0, idx), "UTF-8"),
                      URLDecoder.decode(pair.substring(idx + 1), "UTF-8"));
    }
    return query_pairs;
  }
}

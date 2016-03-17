package eventstore.persistence;

import com.datastax.driver.core.*;
import com.datastax.driver.core.policies.ConstantReconnectionPolicy;
import eventstore.shared.AbstractEventPersistenceVerticle;
import io.vertx.core.Handler;
import io.vertx.core.eventbus.Message;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.apache.commons.lang3.StringUtils;

import java.net.InetSocketAddress;
import java.util.UUID;

import static eventstore.shared.constants.MessageFields.*;


public class CassandraEventPersistenceVerticle extends AbstractEventPersistenceVerticle {
  private Session session;
  private PreparedStatement statement;
  private PreparedStatement queryByEventType;

  @Override
  public void start() throws Exception {
    super.start();
    vertx.executeBlocking(objectFuture -> {
      connectToCassandra();
      objectFuture.succeeded();
    }, tAsyncResult -> { });
  }

  @Override
  protected Handler<Message<Object>> readPersistedEventsConsumer() {
    return message -> {
      final JsonObject body = (JsonObject) message.body();
      logger.debug(String.format("consume read.persisted.events: %s", body.encodePrettily()));

      final String eventType = body.getString(EVENT_TYPE_FIELD);

      final ResultSet results;
      if (StringUtils.isNotEmpty(eventType)) {
        final BoundStatement boundStatement = new BoundStatement(queryByEventType);
        results = session.execute(boundStatement.bind(eventType));
      }
      else {
        results = session.execute("SELECT * FROM simplex.eventlog;");
      }
      final JsonArray response = new JsonArray();
      for (final Row row : results) {
        final JsonObject obj = new JsonObject()
            .put(EVENT_ID_FIELD, row.getUUID(EVENT_ID_FIELD).toString())
            .put(EVENT_CREATED_AT_FIELD, Integer.valueOf(row.getString(EVENT_CREATED_AT_FIELD)))
            .put(EVENT_TYPE_FIELD, row.getString(EVENT_TYPE_FIELD))
            .put(EVENT_DATA_FIELD, new JsonObject(row.getString(EVENT_DATA_FIELD)));
        response.add(obj);
      }

      message.reply(response);
    };
  }

  @Override
  protected Handler<Message<Object>> writeStoreEventsConsumer() {
    return message -> {
      final JsonArray body = (JsonArray) message.body();
      saveEventIfNotDuplicated(body);
      message.reply(true);
    };
  }

  private void saveEventIfNotDuplicated(final JsonArray body) {
    logger.debug(String.format("persisted %s", body.encodePrettily()));
    if (!body.isEmpty()) {
      final JsonObject first = body.getJsonObject(0);
      body.forEach(o -> {
        final JsonObject event = (JsonObject) o;
        final BoundStatement boundStatement = new BoundStatement(statement);
        session.executeAsync(boundStatement.bind(
            UUID.fromString(event.getString(EVENT_ID_FIELD)),
            String.valueOf(event.getInteger(EVENT_CREATED_AT_FIELD)),
            event.getString(EVENT_TYPE_FIELD),
            event.getJsonObject(EVENT_DATA_FIELD).encode()));
        eventBus.publish(String.format("/stream/%s?eventType=%s",
                                       first.getString(EVENT_STREAM_NAME_FIELD), first.getString(EVENT_TYPE_FIELD)),
                         event);
      });
    }
  }

  private void connectToCassandra() {
    try {
      Thread.sleep(2000);
      System.out.println("connecting...");
      final Cluster cluster = Cluster.builder()
                                     .addContactPointsWithPorts(new InetSocketAddress("127.0.0.1", 9042))
                                     .withReconnectionPolicy(new ConstantReconnectionPolicy(200))
                                     .withSocketOptions(new SocketOptions().setConnectTimeoutMillis(10000))
                                     .build();
      final Metadata metadata = cluster.getMetadata();
      System.out.printf("Connected to cluster: %s\n",
                        metadata.getClusterName());
      for (final Host host : metadata.getAllHosts()) {
        System.out.printf("Datacenter: %s; Host: %s; Rack: %s\n",
                          host.getDatacenter(), host.getAddress(), host.getRack());
      }
      session = cluster.connect();
      session.execute("CREATE KEYSPACE IF NOT EXISTS simplex WITH replication " +
                      "= {'class':'SimpleStrategy', 'replication_factor':3};");
      session.execute(
          "CREATE TABLE IF NOT EXISTS simplex.eventlog (" +
          "id uuid PRIMARY KEY," +
          "createdAt text," +
          "eventType text," +
          "data text" +
          ");");
      session.execute("CREATE INDEX eventlog_eventType " +
                      "   ON simplex.eventlog (eventType);");
      statement = session.prepare(
          "INSERT INTO simplex.eventlog (id, createdAt, eventType, data) " +
          "VALUES (?, ?, ?, ?);");
      queryByEventType = session.prepare("SELECT * FROM simplex.eventlog where eventType = ?;");
    }
    catch (final Exception e) {
      System.out.println(e.getMessage());
      System.out.println("connect retry...");
      connectToCassandra();
    }
  }
}
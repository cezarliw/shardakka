package shardakka.keyvalue

import java.util
import java.util.Optional

import akka.actor._
import akka.contrib.pattern.{ ClusterSingletonProxy, ClusterSingletonManager }
import akka.pattern.ask
import akka.util.Timeout
import com.github.benmanes.caffeine.cache.Caffeine
import im.actor.serialization.ActorSerializer
import shardakka.{ StringCodec, Codec, ShardakkaExtension }

import scala.collection.JavaConverters._
import scala.compat.java8.OptionConverters._
import scala.concurrent.Future

private case object End

final case class SimpleKeyValueJava[A](underlying: SimpleKeyValue[A]) {
  import underlying.system.dispatcher

  def upsert(key: String, value: A, timeout: Timeout): Future[Unit] = underlying.upsert(key, value)(timeout)

  def delete(key: String, timeout: Timeout): Future[Unit] = underlying.delete(key)(timeout)

  def get(key: String, timeout: Timeout): Future[Optional[A]] = underlying.get(key)(timeout) map (_.asJava)

  def getKeys(timeout: Timeout): Future[util.List[String]] = underlying.getKeys()(timeout) map (_.asJava)
}

final case class SimpleKeyValue[A](
  name:              String,
  private val root:  ActorRef,
  private val proxy: ActorRef,
  private val codec: Codec[A]
)(implicit private[keyvalue] val system: ActorSystem) {
  import system.dispatcher

  def upsert(key: String, value: A)(implicit timeout: Timeout): Future[Unit] =
    (proxy ? ValueCommands.Upsert(key, codec.toBytes(value))) map (_ ⇒ ())

  def delete(key: String)(implicit timeout: Timeout): Future[Unit] =
    (proxy ? ValueCommands.Delete(key)) map (_ ⇒ ())

  def get(key: String)(implicit timeout: Timeout): Future[Option[A]] =
    (proxy ? ValueQueries.Get(key)).mapTo[ValueQueries.GetResponse] map (_.value.map(codec.fromBytes))

  def getKeys()(implicit timeout: Timeout): Future[Seq[String]] =
    (proxy ? RootQueries.GetKeys()).mapTo[RootQueries.GetKeysResponse] map (_.keys)

  def asJava(): SimpleKeyValueJava[A] = SimpleKeyValueJava(this)

  private[keyvalue] def shutdown(): Unit = {
    proxy ! End
    root ! PoisonPill
    proxy ! PoisonPill
  }
}

trait SimpleKeyValueExtension {
  this: ShardakkaExtension ⇒
  ActorSerializer.register(5201, classOf[RootEvents.KeyCreated])
  ActorSerializer.register(5202, classOf[RootEvents.KeyDeleted])

  ActorSerializer.register(5301, classOf[ValueCommands.Upsert])
  ActorSerializer.register(5302, classOf[ValueCommands.Delete])
  ActorSerializer.register(5303, classOf[ValueCommands.Ack])

  ActorSerializer.register(5401, classOf[ValueQueries.Get])
  ActorSerializer.register(5402, classOf[ValueQueries.GetResponse])

  ActorSerializer.register(5501, classOf[ValueEvents.ValueUpdated])
  ActorSerializer.register(5502, classOf[ValueEvents.ValueDeleted])

  private val kvs = Caffeine.newBuilder().build[String, SimpleKeyValue[_]]()

  def simpleKeyValue[A](name: String, codec: Codec[A])(implicit system: ActorSystem): SimpleKeyValue[A] = {
    Option(kvs.getIfPresent(name)) match {
      case Some(kv) ⇒ kv.asInstanceOf[SimpleKeyValue[A]]
      case None ⇒
        val actorName = s"SimpleKeyValueRoot-$name"

        val (manager, proxy) =
          if (system.settings.ProviderClass.contains("ClusterActorRefProvider")) {
            val mgr = system.actorOf(
              ClusterSingletonManager.props(
                singletonProps = SimpleKeyValueRoot.props(name),
                singletonName = name,
                terminationMessage = End,
                role = None
              ), name = actorName
            )

            val prx = system.actorOf(
              ClusterSingletonProxy.props(singletonPath = s"/user/$actorName/$name", role = None),
              name = s"SimpleKeyValueRoot-$name-Proxy"
            )

            (mgr, prx)
          } else {
            val root = system.actorOf(SimpleKeyValueRoot.props(name), actorName)

            (root, root)
          }

        val kv = SimpleKeyValue(name, manager, proxy, codec)
        kvs.put(name, kv)
        kv
    }
  }

  def simpleKeyValue(name: String)(implicit system: ActorSystem): SimpleKeyValue[String] =
    simpleKeyValue(name, StringCodec)

  def shutdownKeyValue(name: String) = Option(kvs.getIfPresent(name)) foreach { kv ⇒
    kv.shutdown()
    kvs.invalidate(name)
  }
}


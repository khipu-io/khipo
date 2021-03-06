package khipu.util.cache.async

import scala.collection.JavaConverters._
import scala.collection.immutable
import scala.concurrent.{ Future, Promise }

/**
 * General interface implemented by all akka-http cache implementations.
 */
abstract class Cache[K, V] { cache =>

  /**
   * Returns either the cached Future for the given key or evaluates the given value generating
   * function producing a `Future[V]`.
   */
  def apply(key: K, genValue: () => Future[V]): Future[V]

  /**
   * Returns either the cached Future for the key or evaluates the given function which
   * should lead to eventual completion of the promise.
   */
  def apply(key: K, f: Promise[V] ⇒ Unit): Future[V] =
    apply(key, () => { val p = Promise[V](); f(p); p.future })

  /**
   * Returns either the cached Future for the given key, or applies the given value loading
   * function on the key, producing a `Future[V]`.
   */
  def getOrLoad(key: K, loadValue: K => Future[V]): Future[V]

  /**
   * Returns either the cached Future for the given key or the given value as a Future
   */
  def get(key: K, block: () => V): Future[V] =
    cache.apply(key, () => Future.successful(block()))

  /**
   * Retrieves the future instance that is currently in the cache for the given key.
   * Returns None if the key has no corresponding cache entry.
   */
  def get(key: K): Option[Future[V]]

  def put(key: K, genValue: () => Future[V]): Unit

  /**
   * Removes the cache item for the given key.
   */
  def remove(key: K): Unit

  /**
   * Clears the cache by removing all entries.
   */
  def clear(): Unit

  /**
   * Returns the set of keys in the cache, in no particular order
   * Should return in roughly constant time.
   * Note that this number might not reflect the exact keys of active, unexpired
   * cache entries, since expired entries are only evicted upon next access
   * (or by being thrown out by a capacity constraint).
   */
  def keys: immutable.Set[K]

  /**
   * Returns the upper bound for the number of currently cached entries.
   * Note that this number might not reflect the exact number of active, unexpired
   * cache entries, since expired entries are only evicted upon next access
   * (or by being thrown out by a capacity constraint).
   */
  def size(): Int
}


package cn.edu.ubaa.auth

class RedisCookieStorageFactory(private val redisUri: String) : ManagedCookieStorageFactory {
  override fun create(subject: String): ManagedCookieStorage = RedisCookieStorage(redisUri, subject)

  override suspend fun clearSubject(subject: String) {
    RedisCookieStorage.clearSubject(redisUri, subject)
  }
}

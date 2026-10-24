package com.zjh.akali.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException

class MarkRepository(
    private val dao: EntryMarkDao,
    private val server: ServerApi? = null,
) {
    private val lock = Mutex()

    val marks: Flow<List<EntryMark>> = dao.observeAll()

    /** 先存在手机上（界面马上变），再等同步时上传。 */
    suspend fun set(code: String, key: String, state: MarkState) = withContext(Dispatchers.IO) {
        dao.upsert(EntryMark(code, key, state.wire, pending = true))
    }

    /**
     * 同步标记：先上传还没上传的，再取服务器上 [since] 之后的变化。
     * 还没上传的本地标记不会被服务器上的旧状态盖掉。返回服务器当前的版本号。
     */
    suspend fun sync(since: Long): Long = lock.withLock {
        withContext(Dispatchers.IO) {
            val api = server ?: throw IOException("没有配置服务器")
            val pending = dao.pending()
            pending.chunked(500).forEach { batch ->
                api.putMarks(batch.map { RemoteMark(it.code, it.entryKey, it.state) })
                batch.forEach { dao.markSynced(it.code, it.entryKey, it.state) }
            }
            val payload = api.marksChanges(if (since < 0) 0 else since)
            for (r in payload.marks) {
                if (dao.find(r.code, r.key)?.pending == true) continue
                dao.upsert(EntryMark(r.code, r.key, r.state, pending = false))
            }
            payload.rev
        }
    }
}

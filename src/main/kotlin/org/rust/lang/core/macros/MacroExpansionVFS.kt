/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.macros

import com.intellij.openapi.util.io.BufferExposingByteArrayInputStream
import com.intellij.openapi.util.io.FileAttributes
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.impl.local.LocalFileSystemBase
import com.intellij.util.ArrayUtil
import com.intellij.util.IncorrectOperationException
import com.intellij.util.PathUtilRt
import org.rust.lang.core.macros.MacroExpansionVFS.FSItem.Dir
import org.rust.lang.core.macros.MacroExpansionVFS.FSItem.FSFile
import java.io.*

/**
 * An implementation of [com.intellij.openapi.vfs.VirtualFileSystem] used to store macro expansions.
 *
 * The problem is that we need to store tens of thousands of small files (with macro expansions).
 * A real filesystem is slow, and a lot small files consumes too much of diskspace.
 * [com.intellij.openapi.vfs.ex.temp.TempFileSystem] can't be used because these files should
 * persist between IDE restarts, and because in `TempFileSystem` all files are stored in the RAM.
 *
 * [MacroExpansionVFS] is a "snapshot-only VFS", i.e. is doesn't have any "backend" (like
 * a real FS in [com.intellij.openapi.vfs.LocalFileSystem] or RAM in
 * [com.intellij.openapi.vfs.ex.temp.TempFileSystem]) and file contents are stored only in the
 * snapshot ([com.intellij.openapi.vfs.newvfs.persistent.PersistentFS]).
 */
class MacroExpansionVFS : LocalFileSystemBase() {
    private val root: Dir = Dir(null, "/")

    override fun getProtocol(): String = PROTOCOL
    override fun extractRootPath(path: String): String = "/"
    override fun normalize(path: String): String = path
    override fun getCanonicallyCasedName(file: VirtualFile): String = file.name
    override fun isCaseSensitive(): Boolean = true

    override fun isValidName(name: String): Boolean =
        PathUtilRt.isValidFileName(name, PathUtilRt.Platform.UNIX, false, null)

    @Throws(IOException::class)
    override fun createChildDirectory(requestor: Any?, parent: VirtualFile, dir: String): VirtualFile =
        throw UnsupportedOperationException()

    override fun createChildFile(requestor: Any?, parent: VirtualFile, file: String): VirtualFile =
        throw UnsupportedOperationException()

    @Throws(IOException::class)
    override fun copyFile(requestor: Any?, file: VirtualFile, newParent: VirtualFile, copyName: String): VirtualFile =
        throw UnsupportedOperationException()

    override fun deleteFile(requestor: Any?, file: VirtualFile): Unit =
        throw UnsupportedOperationException()

    @Throws(IOException::class)
    override fun moveFile(requestor: Any?, file: VirtualFile, newParent: VirtualFile): Unit =
        throw UnsupportedOperationException()

    override fun renameFile(requestor: Any?, file: VirtualFile, newName: String): Unit =
        throw UnsupportedOperationException()

    private fun convert(file: VirtualFile): FSItem? {
        val parentFile = file.parent ?: return root

        val parentItem = convert(parentFile)
        return if (parentItem != null && parentItem is Dir) {
            parentItem.findChild(file.name)
        } else {
            null
        }
    }

    private fun convert(path: String): FSItem? {
        val segments = StringUtil.split(path, "/")

        var file: FSItem = root
        for (segment in segments) {
            file = (file as? Dir)?.findChild(segment) ?: return null
        }

        return file
    }

    override fun exists(fileOrDirectory: VirtualFile): Boolean {
        return convert(fileOrDirectory) != null
    }

    override fun list(file: VirtualFile): Array<String> {
        val fsItem = convert(file) as? Dir ?: return ArrayUtil.EMPTY_STRING_ARRAY
        return fsItem.list()
    }

    override fun isDirectory(file: VirtualFile): Boolean {
        return convert(file) is Dir
    }

    override fun getTimeStamp(file: VirtualFile): Long {
        val fsItem = convert(file) ?: return DEFAULT_TIMESTAMP
        return fsItem.timestamp
    }

    override fun setTimeStamp(file: VirtualFile, timeStamp: Long) {
        val fsItem = convert(file) ?: return
        fsItem.timestamp = if (timeStamp > 0) timeStamp else System.currentTimeMillis()
    }

    override fun isWritable(file: VirtualFile): Boolean = true

    override fun setWritable(file: VirtualFile, writableFlag: Boolean) {}

    @Throws(IOException::class)
    override fun contentsToByteArray(file: VirtualFile): ByteArray {
        val fsItem = convert(file) ?: throw FileNotFoundException(file.path + " (No such file or directory)")
        if (fsItem !is FSFile) throw FileNotFoundException(file.path + " (Is a directory)")
        return fsItem.fetchAndRemoveContent()
            ?: throw FileNotFoundException(file.path + " (Content is not provided)")
    }

    @Throws(IOException::class)
    override fun getInputStream(file: VirtualFile): InputStream {
        return BufferExposingByteArrayInputStream(contentsToByteArray(file))
    }

    @Throws(IOException::class)
    override fun getOutputStream(file: VirtualFile,
                                 requestor: Any?,
                                 modStamp: Long,
                                 timeStamp: Long): OutputStream {
        throw UnsupportedOperationException()
    }

    override fun getLength(file: VirtualFile): Long {
        val fsItem = convert(file) as? FSFile ?: return DEFAULT_LENGTH
        return fsItem.length.toLong()
    }

    override fun getAttributes(file: VirtualFile): FileAttributes? {
        val item = convert(file) ?: return null
        val length = ((item as? FSFile)?.length ?: 0).toLong()
        return FileAttributes(item is Dir, false, false, false, length, item.timestamp, true)
    }

    override fun replaceWatchedRoots(watchRequests: Collection<WatchRequest>,
                                     recursiveRoots: Collection<String>?,
                                     flatRoots: Collection<String>?): Set<WatchRequest> {
        throw IncorrectOperationException()
    }

    sealed class FSItem {
        abstract val parent: Dir?
        abstract var name: String
        abstract var timestamp: Long

        protected fun bumpTimestamp() {
            timestamp = Math.min(System.currentTimeMillis(), timestamp + 1)
        }

        override fun toString(): String = javaClass.simpleName + ": " + name

        open class Dir(
            override var parent: Dir?,
            override var name: String,
            @get:Synchronized
            @set:Synchronized
            override var timestamp: Long = System.currentTimeMillis()
        ) : FSItem() {
            protected open val children: MutableList<FSItem> = mutableListOf()

            @Synchronized
            fun copyChildren(): List<FSItem> = children.map { it }

            @Synchronized
            fun findChild(name: String): FSItem? = children.find { it.name == name }

            @Synchronized
            fun addChild(item: FSItem, bump: Boolean = false, override: Boolean = false) {
                check(item.parent == this)
                check(item.name.isNotEmpty())
                if (override) {
                    children.removeIf { it.name == item.name }
                } else {
                    check(children.find { it.name == item.name } == null) { "File `${item.name}` already exists" }
                }
                children.add(item)
                if (bump) {
                    bumpTimestamp()
                }
            }

            fun addChildFile(name: String, bump: Boolean = false): FSFile =
                FSFile(this, name).also { addChild(it, bump) }

            fun addChildDir(name: String, bump: Boolean = false): Dir =
                Dir(this, name).also { addChild(it, bump) }

            @Synchronized
            fun removeChild(name: String, bump: Boolean = false) {
                children.removeIf { it.name == name }
                if (bump) {
                    bumpTimestamp()
                }
            }

            @Synchronized
            fun list(): Array<String> = children.map { it.name }.toTypedArray()

            @Synchronized
            fun clear(bump: Boolean = false) {
                children.clear()
                if (bump) {
                    bumpTimestamp()
                }
            }

            class DummyDir(
                parent: Dir?,
                name: String,
                timestamp: Long = System.currentTimeMillis()
            ) : Dir(parent, name, timestamp) {
                override val children: MutableList<FSItem>
                    get() {
                        parent?.removeChild(name, bump = false)
                        return mutableListOf()
                    }
            }

            fun dummy(): Dir = DummyDir(parent, name, timestamp)
        }

        class FSFile(
            override val parent: Dir,
            override var name: String,
            override var timestamp: Long = System.currentTimeMillis(),
            var length: Int = 0,
            var tempContent: ByteArray? = null
        ) : FSItem() {

            @Synchronized
            fun setContent(content: ByteArray) {
                tempContent = content
                length = content.size
                bumpTimestamp()
            }

            @Synchronized
            @Throws(FileNotFoundException::class)
            fun fetchAndRemoveContent(): ByteArray? {
                val tmp = tempContent ?: run {
                    parent.removeChild(name, bump = true)
                    return null
                }
                tempContent = null
                return tmp
            }
        }
    }

    fun createFileWithContent(path: String, content: String) {
        val file = File(path)
        val parent = convert(file.parent) ?: throw FileNotFoundException(file.parent)
        check(parent is Dir)
        val item = parent.addChildFile(file.name)
        item.setContent(content.toByteArray())
    }

    fun setFileContent(path: String, content: String) {
        val item = convert(path) ?: throw FileNotFoundException(path)
        check(item is FSFile)
        item.setContent(content.toByteArray())
    }

    fun createDirectory(path: String) {
        val file = File(path)
        val parent = convert(file.parent) ?: throw FileNotFoundException(file.parent)
        check(parent is Dir)
        parent.addChildDir(file.name, bump = true)
    }

    fun createDirectoryIfNotExistsOrDummy(path: String) {
        val file = File(path)
        val parent = convert(file.parent) ?: throw FileNotFoundException(file.parent)
        check(parent is Dir)
        val name = file.name
        val child = parent.findChild(name)
        if (child == null || child is Dir.DummyDir) {
            if (child is Dir.DummyDir) {
                parent.removeChild(name)
            }
            parent.addChildDir(name, bump = true)
        }
    }

    fun setDirectory(path: String, dir: Dir, override: Boolean = true) {
        val file = File(path)
        val parent = convert(file.parent) ?: throw FileNotFoundException(file.parent)
        check(parent is Dir)
        dir.parent = parent
        dir.name = file.name
        parent.addChild(dir, bump = true, override = override)
    }

    fun makeDummy(path: String) {
        setDirectory(path, getDirectory(path).dummy())
    }

    fun getDirectory(path: String): Dir {
        return convert(path) as? Dir ?: throw FileNotFoundException(path)
    }

    fun deleteFile(path: String) {
        val file = File(path)
        val parent = convert(file.parent) ?: return
        check(parent is Dir)
        parent.removeChild(file.name, bump = true)
    }

    fun cleanDirectory(path: String, bump: Boolean = true) {
        val dir = convert(path) ?: throw FileNotFoundException(path)
        check(dir is Dir)
        dir.clear(bump)
    }

    fun exists(path: String): Boolean = convert(path) != null
    fun isFile(path: String): Boolean = convert(path) is FSFile

    companion object {
        private const val PROTOCOL: String = "rustmacros"
        fun getInstance(): MacroExpansionVFS {
            return VirtualFileManager.getInstance().getFileSystem(PROTOCOL) as MacroExpansionVFS
        }

        @Throws(IOException::class)
        fun writeFSItem(data: DataOutput, f: FSItem) {
            // TODO proper synchronization
            data.writeBoolean(f is Dir)
            data.writeUTF(f.name)
            data.writeLong(f.timestamp)
            when (f) {
                is Dir -> {
                    val children = f.copyChildren()
                    data.writeInt(children.size)
                    for (child in children) {
                        writeFSItem(data, child)
                    }
                }
                is FSFile -> {
                    val length = f.length
                    data.writeInt(length)
                    val content = f.tempContent?.takeIf { it.size == length }
                    data.writeBoolean(content != null)
                    if (content != null) {
                        data.write(content)
                    }
                }
            }
        }

        @Throws(IOException::class)
        fun readFSItem(data: DataInput, parent: Dir?): FSItem {
            val isDir = data.readBoolean()
            val name = data.readUTF()
            val timestamp = data.readLong()
            return if (isDir) {
                val dir = Dir(parent, name, timestamp)
                val count = data.readInt()
                for (i in 0 until count) {
                    val child = readFSItem(data, dir)
                    dir.addChild(child)
                }
                dir
            } else {
                val length = data.readInt()
                val hasContent = data.readBoolean()
                val content = if (hasContent) {
                    ByteArray(length).also { data.readFully(it) }
                } else {
                    null
                }
                FSFile(parent!!, name, timestamp, length, content)
            }
        }
    }
}

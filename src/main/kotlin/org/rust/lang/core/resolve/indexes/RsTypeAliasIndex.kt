/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.resolve.indexes

import com.intellij.openapi.project.Project
import com.intellij.psi.stubs.AbstractStubIndex
import com.intellij.psi.stubs.IndexSink
import com.intellij.psi.stubs.StubIndexKey
import com.intellij.util.io.KeyDescriptor
import org.rust.ide.search.RsWithMacrosProjectScope
import org.rust.lang.core.psi.RsTypeAlias
import org.rust.lang.core.psi.ext.RsAbstractableOwner
import org.rust.lang.core.psi.ext.owner
import org.rust.lang.core.resolve.RsProcessor
import org.rust.lang.core.stubs.RsFileStub
import org.rust.lang.core.stubs.RsTypeAliasStub
import org.rust.lang.core.types.TyFingerprint
import org.rust.openapiext.getElements

class RsTypeAliasIndex : AbstractStubIndex<TyFingerprint, RsTypeAlias>() {
    override fun getVersion(): Int = RsFileStub.Type.stubVersion
    override fun getKey(): StubIndexKey<TyFingerprint, RsTypeAlias> = KEY
    override fun getKeyDescriptor(): KeyDescriptor<TyFingerprint> = TyFingerprint.KeyDescriptor

    companion object {
        fun findPotentialAliases(
            project: Project,
            tyf: TyFingerprint,
            processor: RsProcessor<RsTypeAlias>
        ): Boolean {
            val aliases = getElements(KEY, tyf, project, RsWithMacrosProjectScope(project))

            // This is basically a hack to make some crates (winapi 0.2) work in a reasonable amount of time
            if (aliases.size > 10) return false

            // I intentionally use `getElements` with intermediate collection instead of `StubIndex.processElements`
            // in order to simplify profiling
            return aliases.any {
                it.owner is RsAbstractableOwner.Free && processor(it)
            }
        }

        fun index(stub: RsTypeAliasStub, sink: IndexSink) {
            val alias = stub.psi
            val typeRef = alias.typeReference ?: return
            TyFingerprint.create(typeRef, emptyList())
                .forEach { sink.occurrence(KEY, it) }
        }

        private val KEY: StubIndexKey<TyFingerprint, RsTypeAlias> =
            StubIndexKey.createIndexKey("org.rust.lang.core.stubs.index.RsTypeAliasIndex")
    }
}

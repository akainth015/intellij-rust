/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.ide.inspections

import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.psi.PsiFile
import org.rust.cargo.project.model.CargoProject
import org.rust.cargo.project.workspace.CargoWorkspace
import org.rust.cargo.project.workspace.FeatureState
import org.rust.cargo.project.workspace.PackageFeature
import org.rust.cargo.project.workspace.PackageOrigin
import org.rust.ide.inspections.fixes.EnableCargoFeaturesFix
import org.rust.lang.core.psi.RsFile
import org.rust.lang.core.psi.ext.containingCargoTarget
import org.rust.lang.core.psi.ext.findCargoProject

class RsMissingFeaturesInspection : RsLocalInspectionTool() {
    override fun checkFile(file: PsiFile, manager: InspectionManager, isOnTheFly: Boolean): Array<ProblemDescriptor?>? {
        if (file !is RsFile) return null

        val cargoProject = file.findCargoProject() ?: return null
        val target = file.containingCargoTarget ?: return null
        if (target.pkg.origin != PackageOrigin.WORKSPACE) return null
        val missingFeatures = collectMissingFeatureForTarget(target)

        return createProblemDescriptors(missingFeatures, manager, file, isOnTheFly, cargoProject)
    }

    private fun collectMissingFeatureForTarget(target: CargoWorkspace.Target): Set<PackageFeature> {
        val missingFeatures = mutableSetOf<PackageFeature>()

        collectMissingFeaturesForPackage(target.pkg, missingFeatures)

        val libTarget = target.pkg.libTarget

        if (libTarget != null && target != libTarget) {
            for (requiredFeature in target.requiredFeatures) {
                if (target.pkg.featureState[requiredFeature] == FeatureState.Disabled) {
                    missingFeatures += PackageFeature(target.pkg, requiredFeature)
                }
            }
        }
        return missingFeatures
    }

    companion object {
        private fun collectMissingFeaturesForPackage(pkg: CargoWorkspace.Package, missingFeatures: MutableSet<PackageFeature>) {
            for (dep in pkg.dependencies) {
                if (dep.pkg.origin == PackageOrigin.WORKSPACE) {
                    for (requiredFeature in dep.requiredFeatures) {
                        if (dep.pkg.featureState[requiredFeature] == FeatureState.Disabled) {
                            missingFeatures += PackageFeature(dep.pkg, requiredFeature)
                        }
                    }
                }
            }
        }

        fun collectMissingFeaturesForPackage(pkg: CargoWorkspace.Package): Set<PackageFeature> {
            val missingFeatures = mutableSetOf<PackageFeature>()
            collectMissingFeaturesForPackage(pkg, missingFeatures)
            return missingFeatures
        }

        fun createProblemDescriptors(
            missingFeatures: Set<PackageFeature>,
            manager: InspectionManager,
            file: PsiFile,
            isOnTheFly: Boolean,
            cargoProject: CargoProject
        ): Array<ProblemDescriptor?> {
            return if (missingFeatures.isEmpty()) {
                ProblemDescriptor.EMPTY_ARRAY
            } else {
                arrayOf(
                    manager.createProblemDescriptor(
                        file,
                        "Missing features: ${missingFeatures.joinToString()}",
                        isOnTheFly,
                        arrayOf(EnableCargoFeaturesFix(cargoProject, missingFeatures)),
                        ProblemHighlightType.WARNING
                    )
                )
            }
        }
    }
}

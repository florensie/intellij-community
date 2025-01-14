// Copyright 2000-2021 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

package org.jetbrains.kotlin.idea.debugger

import com.intellij.debugger.DebuggerManagerEx
import com.intellij.debugger.engine.JavaStackFrame
import com.intellij.debugger.engine.events.DebuggerCommandImpl
import com.intellij.debugger.impl.DebuggerUtilsEx
import com.intellij.debugger.settings.DebuggerSettings
import com.intellij.debugger.ui.AlternativeSourceNotificationProvider
import com.intellij.ide.util.ModuleRendererFactory
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.ui.EditorNotificationPanel
import com.intellij.ui.EditorNotifications
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.impl.ui.DebuggerUIUtil
import org.jetbrains.kotlin.idea.stubindex.PackageIndexUtil.findFilesWithExactPackage
import org.jetbrains.kotlin.psi.KtFile

class KotlinAlternativeSourceNotificationProvider(private val myProject: Project) :
    EditorNotifications.Provider<EditorNotificationPanel>() {
    override fun getKey(): Key<EditorNotificationPanel> {
        return KEY
    }

    override fun createNotificationPanel(file: VirtualFile, fileEditor: FileEditor): EditorNotificationPanel? {
        if (!DebuggerSettings.getInstance().SHOW_ALTERNATIVE_SOURCE) {
            return null
        }

        val session = XDebuggerManager.getInstance(myProject).currentSession
        if (session == null) {
            AlternativeSourceNotificationProvider.setFileProcessed(file, false)
            return null
        }

        val position = session.currentPosition
        if (file != position?.file) {
            AlternativeSourceNotificationProvider.setFileProcessed(file, false)
            return null
        }

        if (DumbService.getInstance(myProject).isDumb) return null

        val ktFile = PsiManager.getInstance(myProject).findFile(file) as? KtFile ?: return null

        val packageFqName = ktFile.packageFqName
        val fileName = ktFile.name

        val alternativeKtFiles = findFilesWithExactPackage(
            packageFqName,
            GlobalSearchScope.allScope(myProject),
            myProject,
        ).filterTo(HashSet()) { it.name == fileName }

        AlternativeSourceNotificationProvider.setFileProcessed(file, true)

        if (alternativeKtFiles.size <= 1) {
            return null
        }

        val currentFirstAlternatives: Collection<KtFile> = listOf(ktFile) + alternativeKtFiles.filter { it != ktFile }

        val locationDeclName: String? = when (val frame = session.currentStackFrame) {
            is JavaStackFrame -> {
                val location = frame.descriptor.location
                location?.declaringType()?.name()
            }
            else -> null
        }

        return AlternativeSourceNotificationPanel(currentFirstAlternatives, myProject, file, locationDeclName)
    }

    private class AlternativeSourceNotificationPanel(
        alternatives: Collection<KtFile>,
        project: Project,
        file: VirtualFile,
        locationDeclName: String?,
    ) : EditorNotificationPanel() {
        private class ComboBoxFileElement(val ktFile: KtFile) {
            private val label: String by lazy(LazyThreadSafetyMode.NONE) {
                val factory = ModuleRendererFactory.findInstance(ktFile)
                factory.getModuleTextWithIcon(ktFile)?.text ?: ""
            }

            override fun toString(): String = label
        }

        init {
            text = KotlinDebuggerCoreBundle.message("alternative.sources.notification.title", file.name)

            val items = alternatives.map { ComboBoxFileElement(it) }
            myLinksPanel.add(
                ComboBox(items.toTypedArray()).apply {
                    addActionListener {
                        val context = DebuggerManagerEx.getInstanceEx(project).context
                        val session = context.debuggerSession
                        val ktFile = (selectedItem as ComboBoxFileElement).ktFile
                        val vFile = ktFile.containingFile.virtualFile

                        when {
                            session != null && vFile != null ->
                                session.process.managerThread.schedule(
                                    object : DebuggerCommandImpl() {
                                        override fun action() {
                                            if (!StringUtil.isEmpty(locationDeclName)) {
                                                DebuggerUtilsEx.setAlternativeSourceUrl(locationDeclName, vFile.url, project)
                                            }

                                            DebuggerUIUtil.invokeLater {
                                                FileEditorManager.getInstance(project).closeFile(file)
                                                session.refresh(true)
                                            }
                                        }
                                    },
                                )
                            else -> {
                                FileEditorManager.getInstance(project).closeFile(file)
                                ktFile.navigate(true)
                            }
                        }
                    }
                },
            )

            createActionLabel(KotlinDebuggerCoreBundle.message("alternative.sources.notification.hide")) {
                DebuggerSettings.getInstance().SHOW_ALTERNATIVE_SOURCE = false
                AlternativeSourceNotificationProvider.setFileProcessed(file, false)
                val fileEditorManager = FileEditorManager.getInstance(project)
                val editor = fileEditorManager.getSelectedEditor(file)
                if (editor != null) {
                    fileEditorManager.removeTopComponent(editor, this)
                }
            }
        }
    }

    companion object {
        private val KEY = Key.create<EditorNotificationPanel>("KotlinAlternativeSource")
    }
}

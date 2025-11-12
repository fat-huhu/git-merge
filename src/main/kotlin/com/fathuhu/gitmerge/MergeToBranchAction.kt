package com.fathuhu.gitmerge

import com.intellij.execution.impl.ConsoleViewImpl
import com.intellij.execution.process.KillableColoredProcessHandler
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.icons.AllIcons
import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowAnchor
import com.intellij.openapi.wm.ToolWindowManager
import git4idea.branch.GitBranchUtil
import git4idea.config.GitExecutableManager
import git4idea.repo.GitRepositoryManager
import java.io.File
import javax.swing.BoxLayout
import javax.swing.JPanel

class MergeToBranchAction : AnAction() {

    // 自定义 DataKey 用于获取目标分支名
    companion object {
        val TARGET_BRANCH_KEY: DataKey<String> = DataKey.create("Fathuhu.MergeToBranch.TargetBranch")
        val dataKey: DataKey<String> = DataKey.create("Git.Branches")
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val repo = GitRepositoryManager.getInstance(project).repositories.firstOrNull() ?: return

        val currentBranch = GitBranchUtil.getBranchNameOrRev(repo) ?: return

        // 获取目标分支名
        var targetBranch = getSelectedBranchName(e)
        if (targetBranch == null) {
            notify(project, "无法识别目标分支", NotificationType.ERROR)
            return
        }
        if (targetBranch.startsWith("[refs/remotes/origin/")) {
            targetBranch = targetBranch.removePrefix("[refs/remotes/origin/")
        }
        if (targetBranch.endsWith("]")) {
            targetBranch = targetBranch.removeSuffix("]")
        }
        if (currentBranch == targetBranch) {
            notify(project, "当前分支与目标分支相同，无需合并", NotificationType.WARNING)
            return
        }

        // 读取 merge.sh
        val scriptStream = javaClass.getResourceAsStream("/merge.sh") ?: run {
            notify(project, "未找到 merge.sh 文件", NotificationType.ERROR)
            return
        }

        val tempFile = File.createTempFile("merge", ".sh")
        val scriptText = scriptStream.bufferedReader().readText().replace("\r\n", "\n")
        tempFile.writeText(scriptText)
        tempFile.setExecutable(true)

        // 查找 bash
        val bashPath = findBashFromGit(project)
        if (bashPath == null || !File(bashPath).exists()) {
            notify(project, "未找到 bash，请确认 Git 已正确配置", NotificationType.ERROR)
            return
        }

        val command = listOf(bashPath, tempFile.absolutePath, targetBranch, "--push", "--no-ff")

        // 创建控制台
        val consoleView = ConsoleViewImpl(project, true)
        val toolWindowManager = ToolWindowManager.getInstance(project)
        val toolWindow = toolWindowManager.getToolWindow("Git Merge Console")
            ?: toolWindowManager.registerToolWindow("Git Merge Console", true, ToolWindowAnchor.BOTTOM)

        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)

        // ProcessHandler
        val processBuilder = ProcessBuilder(command)
        processBuilder.directory(File(repo.root.path))
        processBuilder.redirectErrorStream(true)
        val process = processBuilder.start()
        val handler = KillableColoredProcessHandler(process, command.joinToString(" "))

        // 停止按钮
        val stopAction = object : AnAction("停止执行", "终止 merge.sh 脚本", AllIcons.Actions.Suspend) {
            override fun actionPerformed(e: AnActionEvent) {
                if (!handler.isProcessTerminated) {
                    handler.destroyProcess()
                    consoleView.print("\n⛔ 已终止执行\n", ConsoleViewContentType.SYSTEM_OUTPUT)
                }
            }

            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = !handler.isProcessTerminated
            }
        }

        val actionGroup = DefaultActionGroup(stopAction)
        val toolbar = ActionManager.getInstance()
            .createActionToolbar("GitMergeToolbar", actionGroup, false)
        toolbar.targetComponent = panel

        panel.add(toolbar.component)
        panel.add(consoleView.component)

        val content = toolWindow.contentManager.factory.createContent(panel, "Merge Output", false)
        toolWindow.contentManager.addContent(content)
        toolWindow.show()

        consoleView.print("执行命令: ${command.joinToString(" ")}\n\n", ConsoleViewContentType.SYSTEM_OUTPUT)

        handler.addProcessListener(object : com.intellij.execution.process.ProcessAdapter() {
            override fun onTextAvailable(
                event: com.intellij.execution.process.ProcessEvent,
                outputType: com.intellij.openapi.util.Key<*>
            ) {
                consoleView.print(event.text, ConsoleViewContentType.NORMAL_OUTPUT)
            }

            override fun processTerminated(event: com.intellij.execution.process.ProcessEvent) {
                val code = event.exitCode
                val msg = if (code == 0)
                    "✅ 成功合并 $currentBranch → $targetBranch"
                else
                    "❌ 合并失败，退出码：$code"
                consoleView.print("\n$msg\n", ConsoleViewContentType.SYSTEM_OUTPUT)
            }
        })
        handler.startNotify()
    }

    private fun findBashFromGit(project: Project): String? {
        val gitPath = GitExecutableManager.getInstance().getPathToGit(project) ?: return null
        val gitFile = File(gitPath)
        val gitDir = gitFile.parentFile?.parentFile ?: return null

        val candidates = listOf(
            File(gitDir, "bin/bash.exe"),
            File(gitDir, "usr/bin/bash.exe"),
            File(gitDir, "usr/bin/bash"),
            File(gitDir, "bin/bash"),
            File("/usr/bin/bash"),
            File("/bin/bash")
        )

        return candidates.firstOrNull { it.exists() }?.absolutePath
    }

    private fun notify(project: Project, msg: String, type: NotificationType) {
        Notifications.Bus.notify(Notification("Git Merge", "Git Merge", msg, type), project)
    }

    private fun getSelectedBranchName(e: AnActionEvent): String? {

        return (e.dataContext as CustomizedDataContext).getCustomizedDelegate().getData(dataKey)
            .toString();
    }
}

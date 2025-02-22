// Copyright 2000-2021 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package org.jetbrains.idea.maven.project

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import org.jetbrains.idea.maven.buildtool.MavenSyncConsole
import org.jetbrains.idea.maven.importing.MavenExtraArtifactType
import org.jetbrains.idea.maven.model.*
import org.jetbrains.idea.maven.server.MavenArtifactResolutionRequest
import org.jetbrains.idea.maven.server.MavenEmbedderWrapper
import org.jetbrains.idea.maven.utils.MavenProcessCanceledException
import org.jetbrains.idea.maven.utils.MavenProgressIndicator
import org.jetbrains.idea.maven.utils.MavenUtil
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

class MavenArtifactDownloader(private val myProject: Project,
                              private val myProjectsTree: MavenProjectsTree,
                              artifacts: Collection<MavenArtifact>?,
                              private val myIndicator: ProgressIndicator?,
                              private val syncConsole: MavenSyncConsole?) {

  private val myArtifacts: Collection<MavenArtifact>? = if (artifacts == null) null else HashSet(artifacts)

  @Throws(MavenProcessCanceledException::class)
  fun downloadSourcesAndJavadocs(mavenProjects: Collection<MavenProject>,
                                 downloadSources: Boolean,
                                 downloadDocs: Boolean,
                                 embeddersManager: MavenEmbeddersManager,
                                 console: MavenConsole): DownloadResult {
    val projectMultiMap = MavenUtil.groupByBasedir(mavenProjects, myProjectsTree)
    val result = DownloadResult()
    for ((baseDir, mavenProjectsForBaseDir) in projectMultiMap.entrySet()) {
      val embedder = embeddersManager.getEmbedder(MavenEmbeddersManager.FOR_DOWNLOAD, baseDir)
      try {
        val chunk = download(mavenProjectsForBaseDir, embedder, downloadSources, downloadDocs, console)
        for (each in mavenProjectsForBaseDir) {
          myProjectsTree.fireArtifactsDownloaded(each!!)
        }
        result.resolvedDocs.addAll(chunk.resolvedDocs)
        result.resolvedSources.addAll(chunk.resolvedSources)
        result.unresolvedDocs.addAll(chunk.unresolvedDocs)
        result.unresolvedSources.addAll(chunk.unresolvedSources)
      }
      finally {
        embeddersManager.release(embedder)
      }
    }
    return result
  }

  @Throws(MavenProcessCanceledException::class)
  private fun download(mavenProjects: Collection<MavenProject>,
                       embedder: MavenEmbedderWrapper,
                       downloadSources: Boolean,
                       downloadDocs: Boolean,
                       console: MavenConsole?): DownloadResult {
    val downloadedFiles: MutableCollection<File> = ConcurrentLinkedQueue()
    return try {
      val types: MutableList<MavenExtraArtifactType> = ArrayList(2)
      if (downloadSources) types.add(MavenExtraArtifactType.SOURCES)
      if (downloadDocs) types.add(MavenExtraArtifactType.DOCS)
      val caption = if (downloadSources && downloadDocs) MavenProjectBundle.message("maven.downloading")
      else if (downloadSources) MavenProjectBundle.message("maven.downloading.sources")
      else MavenProjectBundle.message("maven.downloading.docs")
      myIndicator?.text = caption
      val artifacts = collectArtifactsToDownload(mavenProjects, types, console)
      download(embedder, artifacts, downloadedFiles, console)
    }
    finally {
      // We have to refresh parents of downloaded files, because some additional files may have been downloaded
      val filesToRefresh: MutableSet<File> = HashSet()
      for (file in downloadedFiles) {
        filesToRefresh.add(file)
        filesToRefresh.add(file.parentFile)
      }
      LocalFileSystem.getInstance().refreshIoFiles(filesToRefresh, true, false, null)
    }
  }

  private fun collectArtifactsToDownload(mavenProjects: Collection<MavenProject>,
                                         types: List<MavenExtraArtifactType>,
                                         console: MavenConsole?): Map<MavenId, DownloadData> {
    val result: MutableMap<MavenId, DownloadData> = HashMap()
    val dependencyTypesFromSettings: MutableSet<String> = HashSet()
    if (!ReadAction.compute<Boolean, RuntimeException> {
        if (myProject.isDisposed) return@compute false
        dependencyTypesFromSettings.addAll(MavenProjectsManager.getInstance(myProject).importingSettings.dependencyTypesAsSet)
        true
      }) {
      return result
    }
    for (eachProject in mavenProjects) {
      val repositories = eachProject.remoteRepositories
      for (eachDependency in eachProject.dependencies) {
        if (myArtifacts != null && !myArtifacts.contains(eachDependency)) continue
        if (MavenConstants.SCOPE_SYSTEM.equals(eachDependency.scope, ignoreCase = true)) continue
        if (myProjectsTree.findProject(eachDependency.mavenId) != null) continue
        val dependencyType = eachDependency.type
        if (!dependencyTypesFromSettings.contains(dependencyType)
            && !eachProject.getDependencyTypesFromImporters(SupportedRequestType.FOR_IMPORT).contains(dependencyType)) {
          continue
        }
        val id = eachDependency.mavenId
        var data = result[id]
        if (data == null) {
          data = DownloadData()
          result[id] = data
        }
        data.repositories.addAll(repositories)
        for (eachType in types) {
          val classifierAndExtension = eachProject.getClassifierAndExtension(eachDependency, eachType)
          val classifier = eachDependency.getFullClassifier(classifierAndExtension.first)
          val extension = classifierAndExtension.second
          data.classifiersWithExtensions.add(DownloadElement(classifier, extension, eachType))
        }
      }
    }
    return result
  }

  @Throws(MavenProcessCanceledException::class)
  private fun download(embedder: MavenEmbedderWrapper,
                       toDownload: Map<MavenId, DownloadData>,
                       downloadedFiles: MutableCollection<File>,
                       console: MavenConsole?): DownloadResult {
    val result = DownloadResult()
    result.unresolvedSources.addAll(toDownload.keys)
    result.unresolvedDocs.addAll(toDownload.keys)
    val requests = ArrayList<MavenArtifactResolutionRequest>()
    for ((id, data) in toDownload) {
      myIndicator?.checkCanceled()
      for (eachElement in data.classifiersWithExtensions) {
        val info = MavenArtifactInfo(id, eachElement.extension, eachElement.classifier)
        val request = MavenArtifactResolutionRequest(info, ArrayList(data.repositories))
        requests.add(request)
      }
    }
    val artifacts = embedder.resolveArtifacts(requests, myIndicator, syncConsole, console)
    for (artifact in artifacts) {
      val file = artifact.file
      if (file.exists()) {
        downloadedFiles.add(file)
        val mavenId = MavenId(artifact.groupId, artifact.artifactId, artifact.version)
        if (MavenExtraArtifactType.SOURCES.defaultClassifier == artifact.classifier) {
          result.resolvedSources.add(mavenId)
          result.unresolvedSources.remove(mavenId)
        }
        else {
          result.resolvedDocs.add(mavenId)
          result.unresolvedDocs.remove(mavenId)
        }
      }
    }
    return result
  }

  private class DownloadData {
    val repositories = LinkedHashSet<MavenRemoteRepository>()
    val classifiersWithExtensions = LinkedHashSet<DownloadElement>()
  }

  private data class DownloadElement(val classifier: String?, val extension: String?, val type: MavenExtraArtifactType?) {
  }

  // used by third-party plugins
  class DownloadResult {
    @JvmField
    val resolvedSources: MutableSet<MavenId> = ConcurrentHashMap.newKeySet()
    @JvmField
    val resolvedDocs: MutableSet<MavenId> = ConcurrentHashMap.newKeySet()
    @JvmField
    val unresolvedSources: MutableSet<MavenId> = ConcurrentHashMap.newKeySet()
    @JvmField
    val unresolvedDocs: MutableSet<MavenId> = ConcurrentHashMap.newKeySet()
  }

  companion object {
    @Throws(MavenProcessCanceledException::class)
    @JvmStatic
    fun download(project: Project,
                 projectsTree: MavenProjectsTree,
                 mavenProjects: Collection<MavenProject>,
                 artifacts: Collection<MavenArtifact>?,
                 downloadSources: Boolean,
                 downloadDocs: Boolean,
                 embedder: MavenEmbedderWrapper,
                 progressIndicator: MavenProgressIndicator?): DownloadResult {
      val indicator = progressIndicator?.indicator
      val syncConsole = progressIndicator?.syncConsole
      return MavenArtifactDownloader(project, projectsTree, artifacts, indicator, syncConsole)
        .download(mavenProjects, embedder, downloadSources, downloadDocs, null)
    }
  }
}

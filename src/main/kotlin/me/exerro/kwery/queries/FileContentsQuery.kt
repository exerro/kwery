package me.exerro.kwery.queries

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import me.exerro.kwery.*
import me.exerro.observables.Observable
import me.exerro.observables.ObservableSignal
import java.nio.file.FileSystems
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds

/** Query for the contents of a file. */
data class FileContentsQuery(val path: Path): Query<Result<ByteArray>> {
    /**
     * Handler for [FileContentsQuery] queries which also watches for changes.
     * When a file in the specified watchPaths is modified and that path has
     * previously been handled (queried), the changed signal will be emitted
     * that query.
     * @param watchPaths Collection of paths to directories which are being
     *                   watched. Cannot provide paths to files, so if a file
     *                   should be watched, provide the parent directory.
     */
    class WatchingHandler(
        scope: CoroutineScope,
        watchPaths: Collection<Path>,
    ): ObservableQueryHandler<FileContentsQuery, Result<ByteArray>> {
        constructor(scope: CoroutineScope, vararg watchPaths: Path): this(scope, watchPaths.toList())

        context(QueryContext, CoroutineScope)
        override suspend fun handle(query: FileContentsQuery) = withContext(Dispatchers.IO) {
            watchedFiles.add(query.path)
            try {
                Result.success(query.path.toFile().readBytes())
            }
            catch (e: Exception) {
                Result.failure(e)
            }
        }

        override val changed: Observable<(FileContentsQuery) -> Unit>

        private val watchedFiles = mutableSetOf<Path>()

        init {
            val (signal, emit) = ObservableSignal.createSignal<FileContentsQuery>()
            changed = signal

            val watchService = FileSystems.getDefault().newWatchService()

            for (path in watchPaths) {
                path.register(
                    watchService,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY,
                    StandardWatchEventKinds.ENTRY_DELETE
                )
            }

            scope.launch(Dispatchers.IO) {
                while (true) {
                    val key = watchService.take()
                    for (event in key.pollEvents()) {
                        val context = event.context() as Path
                        val directory = key.watchable() as Path
                        val fullPath = directory.resolve(context)

                        if (fullPath in watchedFiles)
                            emit(FileContentsQuery(fullPath))
                    }
                    key.reset()
                }
            }
        }
    }

    /**
     * Default handler for [FileContentsQuery] which reads files once and doesn't
     * watch for changes.
     */
    object DefaultHandler: QueryHandler<FileContentsQuery, Result<ByteArray>> {
        context(QueryContext, CoroutineScope)
        override suspend fun handle(query: FileContentsQuery) = withContext(Dispatchers.IO) {
            try {
                Result.success(query.path.toFile().readBytes())
            }
            catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    /**
     * Handler for [FileContentsQuery] queries which returns a fixed set of
     * files.
     */
    class MockHandler(
        private val files: Map<Path, ByteArray>,
    ): QueryHandler<FileContentsQuery, Result<ByteArray>> {
        constructor(vararg files: Pair<Path, String>):
            this(files.toMap().mapValues { (_, v) -> v.toByteArray() })

        context(QueryContext, CoroutineScope)
        override suspend fun handle(query: FileContentsQuery): Result<ByteArray> {
            return when (val contents = files[query.path]) {
                null -> Result.failure(Exception("File not found"))
                else -> Result.success(contents)
            }
        }
    }

    companion object {
        val prettyPrint = QueryGraphPrinter.prettyPrinter<FileContentsQuery> { query ->
            "FileContents\n${query.path}"
        }
    }
}

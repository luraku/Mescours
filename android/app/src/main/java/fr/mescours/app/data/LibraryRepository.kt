package fr.mescours.app.data

import android.content.Context
import fr.mescours.app.model.Child
import fr.mescours.app.model.Course
import fr.mescours.app.model.Library
import fr.mescours.app.model.Theme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

/** Stockage local simple : toute la bibliothèque dans un fichier JSON. */
class LibraryRepository(context: Context) {
    private val file = File(context.filesDir, "library.json")
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false; encodeDefaults = true }
    private val mutex = Mutex()
    private val _library = MutableStateFlow(read())
    val library: StateFlow<Library> = _library.asStateFlow()

    private fun read(): Library = runCatching {
        if (file.exists()) json.decodeFromString(Library.serializer(), file.readText()) else Library()
    }.getOrDefault(Library())

    private suspend fun update(transform: (Library) -> Library) = mutex.withLock {
        val next = transform(_library.value)
        withContext(Dispatchers.IO) {
            val tmp = File(file.parentFile, "library.json.tmp")
            tmp.writeText(json.encodeToString(Library.serializer(), next))
            tmp.renameTo(file)
        }
        _library.value = next
    }

    suspend fun addChild(child: Child) = update { it.copy(children = it.children + child) }

    suspend fun deleteChild(childId: String) = update { lib ->
        lib.copy(
            children = lib.children.filterNot { it.id == childId },
            courses = lib.courses.filterNot { it.childId == childId },
        )
    }

    suspend fun addCourse(course: Course) = update { it.copy(courses = it.courses + course) }

    suspend fun deleteCourse(courseId: String) = update { lib ->
        lib.courses.find { it.id == courseId }?.pagePaths?.forEach { File(it).delete() }
        lib.copy(courses = lib.courses.filterNot { it.id == courseId })
    }

    suspend fun updateTheme(courseId: String, themeId: String, transform: (Theme) -> Theme) = update { lib ->
        lib.copy(courses = lib.courses.map { c ->
            if (c.id != courseId) c
            else c.copy(themes = c.themes.map { t -> if (t.id == themeId) transform(t) else t })
        })
    }
}

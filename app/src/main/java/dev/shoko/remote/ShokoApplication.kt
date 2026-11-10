package dev.shoko.remote

import android.app.Application
import dev.shoko.remote.data.AppDatabase

class ShokoApplication : Application() {
    val database by lazy { AppDatabase.open(this) }
}

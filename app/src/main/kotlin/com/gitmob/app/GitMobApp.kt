package com.gitmob.app

import android.app.Application
import android.content.Context
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import okhttp3.OkHttpClient
import javax.inject.Inject
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class GitMobApp : Application(), SingletonImageLoader.Factory {
    @Inject lateinit var httpClient: OkHttpClient

    override fun newImageLoader(context: Context): ImageLoader =
        ImageLoader.Builder(context).components {
            add(OkHttpNetworkFetcherFactory(callFactory = { httpClient }))
        }.build()
}

package com.dashx.sdk

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import com.apollographql.apollo.ApolloCall
import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.api.CustomTypeAdapter
import com.apollographql.apollo.api.CustomTypeValue
import com.apollographql.apollo.api.Input
import com.apollographql.apollo.api.cache.http.HttpCachePolicy
import com.apollographql.apollo.cache.http.ApolloHttpCache
import com.apollographql.apollo.cache.http.DiskLruHttpCacheStore
import com.apollographql.apollo.exception.ApolloException
import com.dashx.*
import com.dashx.type.*
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import okhttp3.*
import java.io.File
import java.util.*


class DashXClient(
    context: Context,
    publicKey: String,
    baseURI: String? = null,
    targetEnvironment: String? = null,
    targetInstallation: String? = null
) {
    private val tag = DashXClient::class.java.simpleName

    // Setup variables
    private var baseURI: String? = null
    private var publicKey: String? = null
    private var targetEnvironment: String? = null
    private var targetInstallation: String? = null

    // Account variables
    private var anonymousUid: String? = null
    private var uid: String? = null
    private var deviceToken: String? = null
    private var identityToken: String? = null

    private var context: Context? = null

    init {
        this.baseURI = baseURI
        this.publicKey = publicKey
        this.targetEnvironment = targetEnvironment
        this.targetInstallation = targetInstallation
        this.context = context

        createApolloClient()
    }

    private var apolloClient = getApolloClient()

    private fun createApolloClient() {
        apolloClient = getApolloClient()
    }

    fun setIdentityToken(identityToken: String) {
        this.identityToken = identityToken
        createApolloClient()
    }

    fun setDeviceToken(deviceToken: String) {
        this.deviceToken = deviceToken
        subscribe()
    }

    private fun getApolloClient(): ApolloClient {
        val file = File(context?.cacheDir, "dashXCache")
        val size: Long = 5 * 1024 * 1024
        val cacheStore = DiskLruHttpCacheStore(file, size)
        val gsonCustomTypeAdapter = object : CustomTypeAdapter<JsonElement> {
            override fun decode(value: CustomTypeValue<*>): JsonElement {
                return try {
                    Gson().toJsonTree(value.value)
                } catch (e: java.lang.Exception) {
                    throw RuntimeException(e)
                }
            }

            override fun encode(value: JsonElement): CustomTypeValue<*> {
                return CustomTypeValue.GraphQLJsonObject(Gson().fromJson(value, Map::class.java) as Map<String, Any>)
            }
        }

        return ApolloClient.builder()
            .serverUrl(baseURI ?: "https://api.dashx.com/graphql")
            .addCustomTypeAdapter(CustomType.JSON, gsonCustomTypeAdapter)
            .httpCache(ApolloHttpCache(cacheStore))
            .defaultHttpCachePolicy(HttpCachePolicy.CACHE_FIRST)
            .okHttpClient(OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val requestBuilder = chain.request().newBuilder()
                        .addHeader("X-Public-Key", publicKey!!)

                    targetEnvironment?.let {
                        requestBuilder.addHeader("X-Target-Environment", it)
                    }

                    targetInstallation?.let {
                        requestBuilder.addHeader("X-Target-Installation", it)
                    }

                    if (identityToken != null) {
                        requestBuilder.addHeader("X-Identity-Token", identityToken!!)
                    }

                    return@addInterceptor chain.proceed(requestBuilder.build())
                }
                .build())
            .build()
    }

    fun generateAnonymousUid(regenerate: Boolean = false) {
        val dashXSharedPreferences: SharedPreferences =
            getDashXSharedPreferences(context!!)
        val anonymousUid =
            dashXSharedPreferences.getString(SHARED_PREFERENCES_KEY_ANONYMOUS_UID, null)
        if (!regenerate && anonymousUid != null) {
            this.anonymousUid = anonymousUid
        } else {
            this.anonymousUid = UUID.randomUUID().toString()
            dashXSharedPreferences.edit()
                .putString(SHARED_PREFERENCES_KEY_ANONYMOUS_UID, this.anonymousUid)
                .apply()
        }
    }

    fun identify(uid: String?, options: HashMap<String, String>? = hashMapOf()) {
        if (uid != null) {
            this.uid = uid
            DashXLog.d(tag, "Set Uid: $uid")
            return
        }

        if (options == null) {
            DashXLog.d(tag, "Cannot be called with null, either pass uid: string or options: object")
            return
        }

        val identifyAccountInput = IdentifyAccountInput(
            Input.fromNullable(uid),
            Input.fromNullable(anonymousUid),
            Input.fromNullable(options["email"]),
            Input.fromNullable(options["phone"]),
            Input.fromNullable(options["name"]),
            Input.fromNullable(options["firstName"]),
            Input.fromNullable(options["lastName"])
        )
        val identifyAccountMutation = IdentifyAccountMutation(identifyAccountInput)

        apolloClient
            .mutate(identifyAccountMutation)
            .enqueue(object : ApolloCall.Callback<IdentifyAccountMutation.Data>() {
                override fun onFailure(e: ApolloException) {
                    DashXLog.d(tag, "Could not identify with: $uid $options")
                    e.printStackTrace()
                }

                override fun onResponse(response: com.apollographql.apollo.api.Response<IdentifyAccountMutation.Data>) {
                    val identifyResponse = response.data?.identifyAccount
                    DashXLog.d(tag, "Sent identify: $identifyResponse")
                }
            })
    }

    fun reset() {
        uid = null
        generateAnonymousUid(regenerate = true)
    }

    fun fetchContent(
        urn: String,
        preview: Boolean? = true,
        language: String? = null,
        fields: List<String>? = null,
        include: List<String>? = null,
        exclude: List<String>? = null,
        onSuccess: (result: JsonObject) -> Unit,
        onError: (error: String) -> Unit
    ) {
        if (!urn.contains('/')) {
            throw Exception("URN must be of form: {contentType}/{content}")
        }

        val urnArray = urn.split('/')
        val content = urnArray[1]
        val contentType = urnArray[0]

        val fetchContentInput = FetchContentInput(
            contentType,
            content,
            Input.fromNullable(preview),
            Input.fromNullable(language),
            Input.fromNullable(fields),
            Input.fromNullable(include),
            Input.fromNullable(exclude)
        )

        val fetchContentQuery = FetchContentQuery(fetchContentInput)

        apolloClient.query(fetchContentQuery)
            .enqueue(object : ApolloCall.Callback<FetchContentQuery.Data>() {
                override fun onFailure(e: ApolloException) {
                    DashXLog.d(tag, "Could not get content for: $urn")
                    onError(e.message ?: "")
                    e.printStackTrace()
                }

                override fun onResponse(response: com.apollographql.apollo.api.Response<FetchContentQuery.Data>) {
                    val content = response.data?.fetchContent
                    if (!response.errors.isNullOrEmpty()) {
                        val errors = response.errors?.map { e -> e.message }.toString()
                        DashXLog.d(tag, errors)
                        onError(errors)
                        return
                    }

                    if (content != null) {
                        onSuccess(content.asJsonObject)
                    }

                    DashXLog.d(tag, "Got content: $content")
                }
            })
    }

    fun searchContent(
        contentType: String,
        returnType: String = "all",
        filter: JsonObject? = null,
        order: JsonObject? = null,
        limit: Int? = null,
        preview: Boolean? = true,
        language: String? = null,
        fields: List<String>? = null,
        include: List<String>? = null,
        exclude: List<String>? = null,
        onSuccess: (result: List<JsonObject>) -> Unit,
        onError: (error: String) -> Unit
    ) {
        val searchContentInput = SearchContentInput(
            contentType,
            returnType,
            Input.fromNullable(filter),
            Input.fromNullable(order),
            Input.fromNullable(limit),
            Input.fromNullable(preview),
            Input.fromNullable(language),
            Input.fromNullable(fields),
            Input.fromNullable(include),
            Input.fromNullable(exclude)
        )

        val searchContentQuery = SearchContentQuery(searchContentInput)

        apolloClient.query(searchContentQuery)
            .enqueue(object : ApolloCall.Callback<SearchContentQuery.Data>() {
                override fun onFailure(e: ApolloException) {
                    DashXLog.d(tag, "Could not get content for: $contentType")
                    e.printStackTrace()
                    onError(e.message ?: "")
                }

                override fun onResponse(response: com.apollographql.apollo.api.Response<SearchContentQuery.Data>) {
                    val content = response.data?.searchContent
                    if (!response.errors.isNullOrEmpty()) {
                        val errors = response.errors?.map { e -> e.message }.toString()
                        DashXLog.d(tag, errors)
                        onError(errors)
                        return
                    }

                    val result = content ?: listOf()
                    onSuccess(result.map { it.asJsonObject })
                    DashXLog.d(tag, "Got content: $content")
                }
            })
    }

    fun fetchCart(
        onSuccess: (result: JsonObject) -> Unit,
        onError: (error: String) -> Unit
    ) {
        val fetchCartInput = FetchCartInput(
            Input.fromNullable(uid),
            Input.fromNullable(anonymousUid),
        )
        val fetchCartQuery = FetchCartQuery(fetchCartInput)

        apolloClient
            .query(fetchCartQuery)
            .enqueue(object : ApolloCall.Callback<FetchCartQuery.Data>() {
                override fun onFailure(e: ApolloException) {
                    DashXLog.d(tag, e.message)
                    e.printStackTrace()
                }

                override fun onResponse(response: com.apollographql.apollo.api.Response<FetchCartQuery.Data>) {
                    val fetchCartResponse = response.data?.fetchCart

                    if (!response.errors.isNullOrEmpty()) {
                        val errors = response.errors?.map { e -> e.message }.toString()
                        DashXLog.d(tag, errors)
                        onError(errors)
                        return
                    }

                    onSuccess(Gson().toJsonTree(fetchCartResponse).asJsonObject)
                }
            })
    }

    fun addItemToCart(
        itemId: String,
        pricingId: String,
        quantity: String,
        reset: Boolean,
        custom: JsonObject? = null,
        onSuccess: (result: JsonObject) -> Unit,
        onError: (error: String) -> Unit
    ) {
        val addItemToCartInput = AddItemToCartInput(
            Input.fromNullable(uid),
            Input.fromNullable(anonymousUid),
            itemId,
            pricingId,
            quantity,
            reset,
            Input.fromNullable(custom)
        )
        val addItemToCartMutation = AddItemToCartMutation(addItemToCartInput)

        apolloClient
            .mutate(addItemToCartMutation)
            .enqueue(object : ApolloCall.Callback<AddItemToCartMutation.Data>() {
                override fun onFailure(e: ApolloException) {
                    DashXLog.d(tag, e.message)
                    e.printStackTrace()
                }

                override fun onResponse(response: com.apollographql.apollo.api.Response<AddItemToCartMutation.Data>) {
                    val addItemToCartResponse = response.data?.addItemToCart

                    if (!response.errors.isNullOrEmpty()) {
                        val errors = response.errors?.map { e -> e.message }.toString()
                        DashXLog.d(tag, errors)
                        onError(errors)
                        return
                    }

                    onSuccess(Gson().toJsonTree(addItemToCartResponse).asJsonObject)
                }
            })
    }

    fun track(event: String, data: HashMap<String, String>? = hashMapOf()) {
        val jsonData = Gson().toJsonTree(data)

        val trackEventInput = TrackEventInput(
            event,
            Input.fromNullable(uid),
            Input.fromNullable(anonymousUid),
            Input.fromNullable(jsonData)
        )
        val trackEventMutation = TrackEventMutation(trackEventInput)

        apolloClient
            .mutate(trackEventMutation)
            .enqueue(object : ApolloCall.Callback<TrackEventMutation.Data>() {
                override fun onFailure(e: ApolloException) {
                    DashXLog.d(tag, e.message)
                    e.printStackTrace()
                }

                override fun onResponse(response: com.apollographql.apollo.api.Response<TrackEventMutation.Data>) {
                    val trackResponse = response.data?.trackEvent
                    DashXLog.d(tag, "Sent event: $event, $trackResponse")
                }
            })
    }

    fun trackAppStarted(fromBackground: Boolean = false) {
        val context = context ?: return

        val packageInfo = getPackageInfo(context)
        val currentBuild = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode
        } else {
            packageInfo.versionCode.toLong()
        }

        fun saveBuildInPreferences() {
            val editor: SharedPreferences.Editor = getDashXSharedPreferences(context).edit()
            editor.putLong(SHARED_PREFERENCES_KEY_BUILD, currentBuild)
            editor.apply()
        }

        val eventProperties =
            hashMapOf("version" to packageInfo.versionName, "build" to currentBuild.toString())
        if (fromBackground) eventProperties.set("from_background", true.toString())

        when {
            getDashXSharedPreferences(context).getLong(
                SHARED_PREFERENCES_KEY_BUILD,
                Long.MIN_VALUE
            ) == Long.MIN_VALUE
            -> {
                track(INTERNAL_EVENT_APP_INSTALLED, eventProperties)
                saveBuildInPreferences()
            }
            getDashXSharedPreferences(context).getLong(
                SHARED_PREFERENCES_KEY_BUILD,
                Long.MIN_VALUE
            ) < currentBuild
            -> {
                track(INTERNAL_EVENT_APP_UPDATED, eventProperties)
                saveBuildInPreferences()
            }
            else -> track(INTERNAL_EVENT_APP_OPENED, eventProperties)
        }
    }

    fun trackAppSession(elapsedTime: Double) {
        val elapsedTimeRounded = elapsedTime / 1000
        val eventProperties = hashMapOf("session_length" to elapsedTimeRounded.toString())
        track(INTERNAL_EVENT_APP_BACKGROUNDED, eventProperties)
    }

    fun trackAppCrashed(exception: Throwable?) {
        val message = exception?.message
        val eventProperties = hashMapOf("exception" to (message ?: ""))
        track(INTERNAL_EVENT_APP_CRASHED, eventProperties)
    }

    fun screen(screenName: String, properties: HashMap<String, String>?) {
        properties?.set("name", screenName)
        track(INTERNAL_EVENT_APP_SCREEN_VIEWED, properties)
    }

    private fun subscribe(token: String) {
        if (deviceToken == null || identityToken == null) {
            DashXLog.d(tag,
                "Subscribe called with deviceToken: $deviceToken and identityToken: $identityToken")
            return
        }

        val subscribeContactInput = SubscribeContactInput(
            uid!!,
            Input.fromNullable("Android"),
            ContactKind.ANDROID,
            Input.fromNullable(token),
            Input.fromNullable(null),
            os_name: String
            os_version: String
            device_model: String
            device_manufacturer: String
            device_uid: String
            device_advertising_uid: String
            is_device_ad_tracking_enabled: Boolean
        )
        val subscribeContactMutation = SubscribeContactMutation(subscribeContactInput)

        apolloClient
            .mutate(subscribeContactMutation)
            .enqueue(object : ApolloCall.Callback<SubscribeContactMutation.Data>() {
                override fun onFailure(e: ApolloException) {
                    DashXLog.d(tag, e.message)
                    e.printStackTrace()
                }

                override fun onResponse(response: com.apollographql.apollo.api.Response<SubscribeContactMutation.Data>) {
                    val subscribeContactResponse = response.data?.subscribeContact
                    DashXLog.d(tag, "Subscribed: $deviceToken, $subscribeContactResponse")
                }
            })
    }

}

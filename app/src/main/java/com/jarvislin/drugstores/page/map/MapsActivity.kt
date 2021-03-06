package com.jarvislin.drugstores.page.map

import android.Manifest.permission.ACCESS_COARSE_LOCATION
import android.Manifest.permission.ACCESS_FINE_LOCATION
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Point
import android.os.Bundle
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Observer
import com.google.android.gms.location.*
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import com.jakewharton.rxbinding2.view.RxView
import com.jarvislin.domain.entity.DrugstoreInfo
import com.jarvislin.domain.entity.Progress
import com.jarvislin.drugstores.R
import com.jarvislin.drugstores.base.BaseActivity
import com.jarvislin.drugstores.extension.*
import com.jarvislin.drugstores.page.detail.DetailActivity
import com.jarvislin.drugstores.page.search.SearchDialogFragment
import com.jarvislin.drugstores.page.search.SearchDialogFragment.Companion.KEY_INFO
import io.reactivex.Flowable
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.Disposable
import io.reactivex.rxkotlin.addTo
import io.reactivex.schedulers.Schedulers
import kotlinx.android.synthetic.main.activity_maps.*
import org.jetbrains.anko.dip
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel
import timber.log.Timber
import java.util.concurrent.TimeUnit


class MapsActivity : BaseActivity(), OnMapReadyCallback {

    override val viewModel: MapViewModel by viewModel()
    private val cacheManager: MarkerCacheManager by inject()
    private val infoWindowView by lazy {
        LayoutInflater.from(this).inflate(R.layout.window, null, false)
    }

    private val positionLatitude get() = map.cameraPosition.target.latitude
    private val positionLongitude get() = map.cameraPosition.target.longitude
    private lateinit var map: GoogleMap
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var myLocation: LatLng? = null
    private var lastClickedMarker: Marker? = null
    private var disposableMarkers: Disposable? = null

    companion object {
        private const val DELAY_MILLISECONDS = 100L
        private const val REQUEST_LOCATION = 5566
        private const val DEFAULT_ZOOM_LEVEL = 15f
        fun start(context: Context) {
            Intent(context, MapsActivity::class.java).apply {
                context.startActivity(this)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_maps)

        // init margin
        val id = resources.getIdentifier("status_bar_height", "dimen", "android");
        if (id > 0) {
            val height = resources.getDimensionPixelSize(id)
            if (height == 0) {
                return
            }
            (layoutSearch.layoutParams as ConstraintLayout.LayoutParams).let {
                val param = it
                param.setMargins(it.marginStart, height + dip(16), it.marginEnd, 0)
                layoutSearch.layoutParams = param
            }
            viewModel.saveStatusBarHeight(height)
        }

        // progress bar
        progressBarTransform.indeterminateDrawable.tint(
            ContextCompat.getColor(
                this,
                R.color.colorAccent
            )
        )

        // map
        val mapFragment = supportFragmentManager.findFragmentById(R.id.map) as SupportMapFragment
        mapFragment.getMapAsync(this)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        fusedLocationClient.lastLocation.addOnSuccessListener { viewModel.saveLastLocation(it) }
        requestLocation()

        // handle search
        RxView.clicks(layoutSearch)
            .throttleClick()
            .subscribe {
                val dialogFragment = SearchDialogFragment()
                dialogFragment.arguments = Bundle().apply {
                    putSerializable(KEY_INFO, viewModel.drugstoreInfo.value?.let { ArrayList(it) }
                        ?: ArrayList<DrugstoreInfo>())
                }
                dialogFragment.show(supportFragmentManager, "Search")
            }.bind(this)


        // download open data
        viewModel.autoUpdate.observe(this, Observer { startDownload() })
        viewModel.downloadProgress.observe(this, Observer { progress ->
            progressBarDownload.progress = (100 * progress.bytesDownloaded / progress.contentLength).toInt()
            if (progress is Progress.Done) {
                progressBarDownload.hide()
                progressBarTransform.show()
                Timber.i("open data downloaded")
                textProgressHint.text = "資料轉換中"
                viewModel.handleLatestOpenData(progress.file)
            }
        })

        startDownload()
    }

    private fun startDownload() {
        progressBarDownload.progress = 0
        progressBarDownload.show()
        progressBarTransform.hide()
        layoutDownloadHint.animate().alpha(1f).start()
        textProgressHint.text = "資料下載中"

        viewModel.fetchOpenData()
    }

    private fun requestLocation() {
        fusedLocationClient.requestLocationUpdates(
            LocationRequest().setInterval(30_000), object : LocationCallback() {
                override fun onLocationResult(result: LocationResult?) {
                    Timber.e("location update")
                    super.onLocationResult(result)
                    result?.let {
                        it.locations.firstOrNull()?.let {
                            myLocation = LatLng(it.latitude, it.longitude)
                            viewModel.saveLastLocation(it)
                        }
                    }
                }
            },
            Looper.getMainLooper()
        )
    }

    override fun onMapReady(googleMap: GoogleMap) {
        map = googleMap

        if ((hasPermission(ACCESS_FINE_LOCATION, ACCESS_COARSE_LOCATION))) {
            enableMyLocation()
        }

        moveTo(myLocation ?: viewModel.getLastLocation())

        viewModel.dataPrepared.observe(this, Observer { done ->
            if (done) {
                viewModel.drugstoreInfo.observe(this, Observer { addMarkers(it) })
                viewModel.fetchNearDrugstoreInfo(positionLatitude, positionLongitude)
                viewModel.countDown()
            }
            layoutDownloadHint.animate().setStartDelay(1_000).alpha(0f).start()
        })

        map.uiSettings.isMapToolbarEnabled = false

        map.setOnInfoWindowClickListener {
            DetailActivity.start(this, cacheManager.getEntireInfo(it))
        }

        map.setOnCameraIdleListener {
            viewModel.fetchNearDrugstoreInfo(positionLatitude, positionLongitude)
        }

        map.setOnCameraMoveStartedListener {
            disposableMarkers?.dispose()
            updateFabColor(R.color.secondaryIcons)
        }

        map.setInfoWindowAdapter(object : GoogleMap.InfoWindowAdapter {
            override fun getInfoContents(marker: Marker): View? {
                lastClickedMarker = marker
                bindView(infoWindowView, cacheManager.getEntireInfo(marker))
                return infoWindowView
            }

            override fun getInfoWindow(p0: Marker): View? = null
        })

        map.setOnMarkerClickListener {
            animateTo(it)
            true
        }

        fab.setOnClickListener { checkPermission() }
    }

    private fun updateFabColor(colorId: Int) {
        ContextCompat.getDrawable(this, R.drawable.ic_my_location)?.apply {
            tint(ContextCompat.getColor(this@MapsActivity, colorId))
            fab.setImageDrawable(this)
        }
    }

    private fun bindView(view: View, info: DrugstoreInfo) {
        view.findViewById<TextView>(R.id.textName).text = info.name
        view.findViewById<TextView>(R.id.textUpdate).text = info.updateAt.toUpdateWording()
        view.findViewById<TextView>(R.id.textAdultAmount).text =
            info.adultMaskAmount.toString()
        view.findViewById<TextView>(R.id.textChildAmount).text =
            info.childMaskAmount.toString()
        view.findViewById<View>(R.id.layoutAdult).background =
            info.adultMaskAmount.toBackground()
        view.findViewById<View>(R.id.layoutChild).background =
            info.childMaskAmount.toBackground()
    }


    private fun addMarkers(drugstores: List<DrugstoreInfo>) {
        disposableMarkers = Flowable.fromIterable(drugstores)
            .subscribeOn(Schedulers.computation())
            .filter { cacheManager.isCached(it).not() }
            .map { info ->
                MarkerInfoManager.getMarkerInfo(info.adultMaskAmount).let {
                    val options = MarkerOptions()
                        .position(LatLng(info.lat, info.lng))
                        .snippet(info.id)
                        .zIndex(it.zIndex)

                    ContextCompat.getDrawable(this, it.drawableId)?.getBitmap()
                        .let { options.icon(BitmapDescriptorFactory.fromBitmap(it)) }
                }.let {
                    Pair(info, it)
                }
            }
            .delay(DELAY_MILLISECONDS, TimeUnit.MILLISECONDS)
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({ pair ->
                pair.let { map.addMarker(it.second) }
                    .also { cacheManager.add(pair.first, it) }
            }, { Timber.e(it) })
            .addTo(compositeDisposable)
    }


    private fun checkPermission() {
        if (hasPermission(ACCESS_FINE_LOCATION, ACCESS_COARSE_LOCATION)) {
            enableMyLocation()
            animateTo(myLocation ?: viewModel.getLastLocation(),
                callback = { updateFabColor(R.color.colorAccent) })
        } else {
            ActivityCompat.requestPermissions(
                this, arrayOf(
                    ACCESS_COARSE_LOCATION,
                    ACCESS_FINE_LOCATION
                )
                ,
                REQUEST_LOCATION
            )
        }
    }


    private fun moveTo(latLng: LatLng) {
        CameraUpdateFactory.newLatLngZoom(latLng, DEFAULT_ZOOM_LEVEL).let {
            map.moveCamera(it)
        }
    }

    private fun animateTo(latLng: LatLng, callback: () -> Unit = {}) {
        CameraUpdateFactory.newLatLngZoom(latLng, DEFAULT_ZOOM_LEVEL).let {
            map.animateCamera(it, object : GoogleMap.CancelableCallback {
                override fun onFinish() {
                    callback.invoke()
                }

                override fun onCancel() {}
            })
        }
    }

    private fun animateTo(marker: Marker) {
        val pointInScreen = map.projection.toScreenLocation(marker.position)
        val newPoint = Point()
        newPoint.x = pointInScreen.x
        newPoint.y = pointInScreen.y - dip(100) // offset, looks good on pixel 3a XD
        val newLatLng = map.projection.fromScreenLocation(newPoint)

        CameraUpdateFactory.newLatLngZoom(newLatLng, map.cameraPosition.zoom).let {
            map.animateCamera(it, 400, object : GoogleMap.CancelableCallback {
                override fun onFinish() {
                    marker.showInfoWindow()
                }

                override fun onCancel() {}
            })
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (hasPermission(ACCESS_FINE_LOCATION, ACCESS_COARSE_LOCATION)) {
            enableMyLocation()
            requestLocation()
        }
    }

    private fun enableMyLocation() {
        map.uiSettings.isMyLocationButtonEnabled = false
        map.isMyLocationEnabled = true
    }

    override fun onBackPressed() {
        if (lastClickedMarker?.isInfoWindowShown == true) {
            lastClickedMarker?.hideInfoWindow()
        } else {
            super.onBackPressed()
        }
    }
}

fun Context.hasPermission(vararg permission: String): Boolean {
    return permission.all {
        ActivityCompat.checkSelfPermission(
            this,
            it
        ) == PackageManager.PERMISSION_GRANTED
    }
}
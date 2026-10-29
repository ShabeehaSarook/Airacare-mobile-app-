package com.airacare.animalsafety

import android.graphics.Color
import android.app.AlertDialog
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.content.res.ColorStateList
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ListView
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.TextViewCompat
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CapturedPetsActivity : ComponentActivity() {
    private lateinit var capturedPetRepository: CapturedPetRepository
    private lateinit var petCaptureRepository: PetCaptureRepository
    private lateinit var capturedPetLocalStore: CapturedPetLocalStore
    private lateinit var summaryText: TextView
    private lateinit var emptyText: TextView
    private lateinit var listContainer: ListView
    private var visibleItems: List<CapturedPetRecord> = emptyList()
    private var loading = false
    private val collectionAdapter = object : BaseAdapter() {
        override fun getCount() = visibleItems.size
        override fun getItem(position: Int) = visibleItems[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
            createRow(getItem(position), convertView)
    }
    private lateinit var allFilterButton: Button
    private lateinit var dogsFilterButton: Button
    private lateinit var catsFilterButton: Button
    private lateinit var ownerId: String
    private var allItems: List<CapturedPetRecord> = emptyList()
    private var activeFilter: String = FILTER_ALL

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_captured_pets)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        val root = findViewById<View>(R.id.collectionRoot)
        val padding = (16 * resources.displayMetrics.density).toInt()
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(padding + bars.left, padding + bars.top, padding + bars.right, bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)
        TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
            findViewById(R.id.collectionHeading), 18, 28, 1, android.util.TypedValue.COMPLEX_UNIT_SP
        )

        ownerId = OwnerIdProvider.get(this)
        Log.i(TAG, "CapturedPetsActivity ownerId=$ownerId")
        capturedPetRepository = CapturedPetRepository()
        petCaptureRepository = PetCaptureRepository()
        capturedPetLocalStore = CapturedPetLocalStore(this)
        summaryText = findViewById(R.id.capturedPetsSummaryText)
        emptyText = findViewById(R.id.capturedPetsEmptyText)
        listContainer = findViewById(R.id.capturedPetsList)
        listContainer.adapter = collectionAdapter
        allFilterButton = findViewById(R.id.filterAllButton)
        dogsFilterButton = findViewById(R.id.filterDogsButton)
        catsFilterButton = findViewById(R.id.filterCatsButton)

        findViewById<View>(R.id.capturedPetsBackButton).setOnClickListener { finish() }
        findViewById<View>(R.id.capturedPetsRefreshButton).setOnClickListener { loadCollection() }
        findViewById<View>(R.id.collectionRetry).setOnClickListener { loadCollection() }
        allFilterButton.setOnClickListener { setFilter(FILTER_ALL) }
        dogsFilterButton.setOnClickListener { setFilter("dog") }
        catsFilterButton.setOnClickListener { setFilter("cat") }

        loadCollection()
    }

    private fun loadCollection() {
        if (loading) return
        loading = true
        findViewById<View>(R.id.collectionLoading).visibility = View.VISIBLE
        findViewById<View>(R.id.collectionRetry).visibility = View.GONE
        findViewById<View>(R.id.capturedPetsRefreshButton).isEnabled = false
        summaryText.text = "Loading collection..."
        Log.i(TAG, "Loading collection owner=$ownerId")
        capturedPetRepository.load(ownerId) { result ->
            if (isDestroyed || isFinishing) return@load
            loading = false
            findViewById<View>(R.id.collectionLoading).visibility = View.GONE
            findViewById<View>(R.id.capturedPetsRefreshButton).isEnabled = true
            result.onSuccess { records ->
                val merged = mergeRecords(records, capturedPetLocalStore.load())
                Log.i(TAG, "Collection loaded firestore=${records.size} merged=${merged.size}")
                allItems = merged
                render()
            }.onFailure { exception ->
                Log.e(TAG, "Collection load failed", exception)
                val localRecords = capturedPetLocalStore.load()
                allItems = localRecords
                render()
                summaryText.text = "Saved pets: ${localRecords.size} (local)"
                findViewById<View>(R.id.collectionRetry).visibility = View.VISIBLE
                Toast.makeText(this, exception.message ?: "Loaded local collection only", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun mergeRecords(firestoreRecords: List<CapturedPetRecord>, localRecords: List<CapturedPetRecord>): List<CapturedPetRecord> {
        val byImagePublicId = LinkedHashMap<String, CapturedPetRecord>()
        localRecords.sortedByDescending { it.capturedAtEpochMillis }.forEach { record ->
            byImagePublicId[record.imagePublicId] = record
        }
        firestoreRecords.sortedByDescending { it.capturedAtEpochMillis }.forEach { record ->
            byImagePublicId[record.imagePublicId] = record
        }
        return byImagePublicId.values.sortedByDescending { it.capturedAtEpochMillis }
    }

    private fun setFilter(filter: String) {
        activeFilter = filter
        render()
    }

    private fun render() {
        visibleItems = allItems.filter { item -> activeFilter == FILTER_ALL || item.label.equals(activeFilter, ignoreCase = true) }
        Log.i(TAG, "Render collection filter=$activeFilter total=${allItems.size} visible=${visibleItems.size}")
        summaryText.text = "Saved pets: ${allItems.size}" + if (activeFilter == FILTER_ALL) "" else "  /  ${visibleItems.size} shown"
        emptyText.visibility = if (visibleItems.isEmpty()) View.VISIBLE else View.GONE
        emptyText.text = if (allItems.isEmpty()) "Your collection is waiting" else "No ${activeFilter}s saved yet"
        allFilterButton.isSelected = activeFilter == FILTER_ALL
        dogsFilterButton.isSelected = activeFilter == "dog"
        catsFilterButton.isSelected = activeFilter == "cat"
        collectionAdapter.notifyDataSetChanged()
    }

    private fun createRow(item: CapturedPetRecord, recycled: View?): View {
        val row = recycled ?: layoutInflater.inflate(R.layout.item_captured_pet, listContainer, false)
        val image = row.findViewById<ImageView>(R.id.capturedPetImage)
        row.findViewById<TextView>(R.id.capturedPetTitleText).text =
            item.label.uppercase(Locale.US)
        row.findViewById<TextView>(R.id.petConfidence).text = "${(item.confidence * 100f).format(1)}%"
        val title = row.findViewById<TextView>(R.id.capturedPetTitleText)
        val confidence = row.findViewById<TextView>(R.id.petConfidence)
        val titleRow = title.parent as LinearLayout
        val largeText = resources.configuration.fontScale > 1.3f
        titleRow.orientation = if (largeText) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        title.layoutParams = LinearLayout.LayoutParams(
            if (largeText) ViewGroup.LayoutParams.MATCH_PARENT else 0,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            if (largeText) 0f else 1f
        )
        confidence.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        row.findViewById<TextView>(R.id.capturedPetTimeText).text =
            "Captured: ${item.capturedAtEpochMillis.asDateTime()}"
        row.findViewById<TextView>(R.id.capturedPetDistanceText).text =
            "Distance: ${item.distanceMeters?.let { "${it.format(1)} m" } ?: "N/A"}"
        row.findViewById<TextView>(R.id.capturedPetRiskText).apply {
            text = "Risk: ${item.riskLevel.name}"
            setTextColor(riskColor(item.riskLevel))
            compoundDrawableTintList = ColorStateList.valueOf(riskColor(item.riskLevel))
            background = GradientDrawable().apply {
                cornerRadius = 8 * resources.displayMetrics.density
                setColor((riskColor(item.riskLevel) and 0x00FFFFFF) or 0x22000000)
            }
        }
        row.findViewById<TextView>(R.id.capturedPetStorageText).text =
            "Storage: ${item.storageProvider.replaceFirstChar { char -> char.uppercase(Locale.US) }}"
        row.findViewById<Button>(R.id.capturedPetDeleteButton).setOnClickListener {
            AlertDialog.Builder(this, android.app.AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                .setTitle("Delete this capture?")
                .setMessage("The saved ${item.label} image and collection record will be removed.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete") { _, _ -> deleteItem(item) }
                .show()
        }
        image.contentDescription = "Saved ${item.label}"
        loadImage(row, item)
        return row
    }

    private fun loadImage(row: View, item: CapturedPetRecord) {
        val image = row.findViewById<ImageView>(R.id.capturedPetImage)
        val progress = row.findViewById<View>(R.id.petImageLoading)
        val retry = row.findViewById<View>(R.id.petImageRetry)
        Glide.with(image).clear(image)
        progress.visibility = View.VISIBLE
        retry.visibility = View.GONE
        retry.setOnClickListener { loadImage(row, item) }
        Glide.with(image).load(item.imageUrl).centerInside()
            .listener(object : RequestListener<Drawable> {
                override fun onLoadFailed(e: GlideException?, model: Any?, target: Target<Drawable>, isFirstResource: Boolean): Boolean {
                    progress.visibility = View.GONE
                    retry.visibility = View.VISIBLE
                    return false
                }
                override fun onResourceReady(resource: Drawable, model: Any, target: Target<Drawable>?, dataSource: DataSource, isFirstResource: Boolean): Boolean {
                    progress.visibility = View.GONE
                    retry.visibility = View.GONE
                    return false
                }
            }).into(image)
    }

    private fun riskColor(riskLevel: RiskLevel): Int {
        return when (riskLevel) {
            RiskLevel.LOW -> Color.rgb(34, 197, 94)
            RiskLevel.MEDIUM -> Color.rgb(245, 158, 11)
            RiskLevel.HIGH -> Color.rgb(220, 38, 38)
            RiskLevel.UNKNOWN -> Color.rgb(215, 222, 230)
        }
    }

    private fun deleteItem(item: CapturedPetRecord) {
        val documentId = item.documentId
        summaryText.text = "Deleting..."
        Log.i(TAG, "Deleting item documentId=$documentId imagePublicId=${item.imagePublicId}")
        petCaptureRepository.deleteCapturedPet(item.imagePublicId) { ok, message ->
            if (!ok) {
                Log.e(TAG, "Cloudinary/backend delete failed message=$message")
                summaryText.text = "Delete failed"
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                return@deleteCapturedPet
            }
            capturedPetLocalStore.delete(item.imagePublicId)
            if (CapturedPetLocalStore.isLocalDocumentId(documentId) || documentId == null) {
                Log.i(TAG, "Delete complete local-only imagePublicId=${item.imagePublicId}")
                Toast.makeText(this, "Deleted", Toast.LENGTH_SHORT).show()
                loadCollection()
                return@deleteCapturedPet
            }
            capturedPetRepository.delete(documentId) { firestoreOk, firestoreMessage ->
                if (firestoreOk) {
                    capturedPetLocalStore.delete(item.imagePublicId)
                    Log.i(TAG, "Delete complete documentId=$documentId")
                    Toast.makeText(this, "Deleted", Toast.LENGTH_SHORT).show()
                    loadCollection()
                } else {
                    Log.e(TAG, "Firestore delete failed message=$firestoreMessage")
                    summaryText.text = "Firestore delete failed"
                    Toast.makeText(this, firestoreMessage, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun Long.asDateTime(): String = DATE_FORMAT.format(Date(this))

    private fun Float.format(decimals: Int): String = "%.${decimals}f".format(Locale.US, this)

    companion object {
        private const val TAG = "CapturedPetsActivity"
        private const val FILTER_ALL = "all"
        private val DATE_FORMAT = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.US)
    }
}

package io.github.panevx.csearchrevamped.translate

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.CompoundButton
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import io.github.panevx.csearchrevamped.Prefs
import io.github.panevx.csearchrevamped.R
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/** Download translation languages ahead of time so live translate works instantly and offline. */
class OfflineLanguagesActivity : AppCompatActivity() {

    private val manager = RemoteModelManager.getInstance()
    private val codes = TranslateLanguage.getAllLanguages().sortedBy { Languages.name(it) }
    private val downloaded = HashSet<String>()
    private val busy = HashSet<String>()
    private lateinit var adapter: LanguageAdapter
    private lateinit var status: TextView
    private lateinit var progress: LinearProgressIndicator
    private lateinit var downloadAll: MaterialButton
    private var allJob: Job? = null

    private fun model(code: String) = TranslateRemoteModel.Builder(code).build()

    private fun conditions(): DownloadConditions =
        DownloadConditions.Builder().apply { if (Prefs.wifiOnly(this@OfflineLanguagesActivity)) requireWifi() }.build()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_offline_languages)
        status = findViewById(R.id.offlineStatus)
        progress = findViewById(R.id.offlineProgress)
        downloadAll = findViewById(R.id.downloadAll)
        adapter = LanguageAdapter()
        findViewById<ListView>(R.id.offlineList).adapter = adapter
        findViewById<CompoundButton>(R.id.wifiOnly).apply {
            isChecked = Prefs.wifiOnly(this@OfflineLanguagesActivity)
            setOnCheckedChangeListener { _, on -> Prefs.setWifiOnly(this@OfflineLanguagesActivity, on) }
        }
        downloadAll.setOnClickListener { if (allJob?.isActive == true) cancelAll() else downloadAll() }
        refresh()
    }

    private fun refresh() {
        lifecycleScope.launch {
            val models = runCatching { manager.getDownloadedModels(TranslateRemoteModel::class.java).await() }
                .getOrDefault(emptySet())
            downloaded.clear()
            downloaded.addAll(models.map { it.language })
            downloaded.add(TranslateLanguage.ENGLISH) // always built in
            updateSummary()
            adapter.notifyDataSetChanged()
        }
    }

    private fun updateSummary() {
        if (allJob?.isActive == true) return
        status.text = getString(R.string.offline_summary, downloaded.size, codes.size)
        progress.isVisible = false
        downloadAll.setText(R.string.offline_download_all)
        downloadAll.isEnabled = downloaded.size < codes.size
    }

    private fun download(code: String) {
        busy.add(code)
        adapter.notifyDataSetChanged()
        lifecycleScope.launch {
            val ok = runCatching { manager.download(model(code), conditions()).await() }.isSuccess
            busy.remove(code)
            if (ok) downloaded.add(code) else failed(code)
            updateSummary()
            adapter.notifyDataSetChanged()
        }
    }

    private fun delete(code: String) {
        busy.add(code)
        adapter.notifyDataSetChanged()
        lifecycleScope.launch {
            runCatching { manager.deleteDownloadedModel(model(code)).await() }
            busy.remove(code)
            downloaded.remove(code)
            updateSummary()
            adapter.notifyDataSetChanged()
        }
    }

    private fun failed(code: String) {
        val reason = if (Prefs.wifiOnly(this)) getString(R.string.offline_failed_wifi) else ""
        Toast.makeText(this, getString(R.string.offline_failed, Languages.name(code)) + reason, Toast.LENGTH_LONG).show()
    }

    private fun downloadAll() {
        val todo = codes.filter { it !in downloaded }
        if (todo.isEmpty()) return
        downloadAll.setText(R.string.offline_stop)
        progress.isVisible = true
        progress.max = todo.size
        allJob = lifecycleScope.launch {
            for ((i, code) in todo.withIndex()) {
                status.text = getString(R.string.offline_downloading, i + 1, todo.size, Languages.name(code))
                progress.setProgressCompat(i, true)
                busy.add(code)
                adapter.notifyDataSetChanged()
                val ok = runCatching { manager.download(model(code), conditions()).await() }.isSuccess
                busy.remove(code)
                if (ok) downloaded.add(code) else { failed(code); break }
                adapter.notifyDataSetChanged()
            }
            allJob = null
            updateSummary()
            adapter.notifyDataSetChanged()
        }
    }

    private fun cancelAll() {
        allJob?.cancel()
        allJob = null
        busy.clear()
        updateSummary()
        adapter.notifyDataSetChanged()
    }

    private inner class LanguageAdapter : BaseAdapter() {
        override fun getCount() = codes.size
        override fun getItem(position: Int) = codes[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: LayoutInflater.from(parent.context).inflate(R.layout.offline_language_item, parent, false)
            val code = codes[position]
            view.findViewById<TextView>(R.id.languageName).text = Languages.name(code)
            val state = view.findViewById<TextView>(R.id.languageState)
            val action = view.findViewById<MaterialButton>(R.id.languageAction)
            when {
                code in busy -> {
                    state.setText(R.string.offline_state_working)
                    action.isVisible = false
                }
                code == TranslateLanguage.ENGLISH -> {
                    state.setText(R.string.offline_state_builtin)
                    action.isVisible = false
                }
                code in downloaded -> {
                    state.setText(R.string.offline_state_ready)
                    action.isVisible = allJob?.isActive != true
                    action.setText(R.string.offline_delete)
                    action.setOnClickListener { delete(code) }
                }
                else -> {
                    state.setText(R.string.offline_state_missing)
                    action.isVisible = allJob?.isActive != true
                    action.setText(R.string.offline_download)
                    action.setOnClickListener { download(code) }
                }
            }
            return view
        }
    }
}

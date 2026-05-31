package com.phoneassistant.ui.recents

import android.app.Application
import android.os.Bundle
import android.view.*
import androidx.appcompat.widget.PopupMenu
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.*
import androidx.recyclerview.widget.*
import com.phoneassistant.MainActivity
import com.phoneassistant.data.*
import com.phoneassistant.databinding.FragmentRecentsBinding
import com.phoneassistant.databinding.ItemCallBinding
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class RecentsViewModel(app: Application) : AndroidViewModel(app) {
    private val callRepo    = CallLogRepo(app)
    private val callerRepo  = CallerIdRepo(app)
    private val storage     = AppStorage(app)
    private var all = listOf<CallEntry>()
    private var filter: CallType? = null
    private val _entries = MutableLiveData<List<CallEntry>>()
    val entries: LiveData<List<CallEntry>> = _entries
    val loading = MutableLiveData(false)

    fun load() = viewModelScope.launch {
        loading.value = true
        all = callRepo.getAll().map { e ->
            if (e.name == null) {
                val info = callerRepo.identify(e.number)
                e.copy(callerInfo = info?.let { i ->
                    listOfNotNull(i.carrier, i.lineType, i.location).joinToString(" • ").takeIf { it.isNotBlank() }
                })
            } else e
        }
        apply(); loading.value = false
    }

    fun setFilter(t: CallType?) { filter = t; apply() }
    fun delete(id: Long) = viewModelScope.launch { callRepo.delete(id); all = all.filter { it.id != id }; apply() }
    fun block(number: String) = viewModelScope.launch { storage.blockNumber(number, "Bloqué depuis l'historique") }
    private fun apply() { _entries.value = if (filter == null) all else all.filter { it.callType == filter } }
}

class CallAdapter(
    private val onCall: (CallEntry) -> Unit,
    private val onSms: (CallEntry) -> Unit,
    private val onLong: (CallEntry, View) -> Unit
) : ListAdapter<CallEntry, CallAdapter.VH>(object : DiffUtil.ItemCallback<CallEntry>() {
    override fun areItemsTheSame(a: CallEntry, b: CallEntry) = a.id == b.id
    override fun areContentsTheSame(a: CallEntry, b: CallEntry) = a == b
}) {
    inner class VH(private val b: ItemCallBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(e: CallEntry) {
            val display = e.name ?: e.callerInfo?.substringBefore(" •") ?: e.number
            b.tvName.text = display
            b.tvSub.text = buildString {
                if (e.name != null) append(e.number)
                e.callerInfo?.let { if (isNotEmpty()) append("  •  "); append(it) }
            }.ifBlank { e.number }
            b.tvTime.text = fmt(e.timestamp)
            b.tvDuration.text = if (e.duration > 0) fmtDur(e.duration) else ""
            val (icon, color) = when (e.callType) {
                CallType.INCOMING -> "↙" to 0xFF4CAF50.toInt()
                CallType.OUTGOING -> "↗" to 0xFF2196F3.toInt()
                CallType.MISSED   -> "↙" to 0xFFF44336.toInt()
                CallType.REJECTED -> "✕" to 0xFFF44336.toInt()
                CallType.BLOCKED  -> "⊘" to 0xFF9E9E9E.toInt()
            }
            b.tvCallIcon.text = icon; b.tvCallIcon.setTextColor(color)
            b.tvInitials.text = display.split(" ").mapNotNull { it.firstOrNull()?.toString() }.take(2).joinToString("").uppercase()
            b.btnCall.setOnClickListener { onCall(e) }
            b.btnSms.setOnClickListener { onSms(e) }
            b.root.setOnLongClickListener { v -> onLong(e, v); true }
        }
        private fun fmtDur(s: Long) = if (s < 60) "${s}s" else "${s/60}min"
        private fun fmt(ts: Long): String {
            val d = System.currentTimeMillis() - ts
            return when {
                d < 3_600_000  -> "${d/60000}min"
                d < 86_400_000 -> SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ts))
                d < 604_800_000 -> SimpleDateFormat("EEE", Locale.FRENCH).format(Date(ts))
                else -> SimpleDateFormat("dd/MM", Locale.getDefault()).format(Date(ts))
            }
        }
    }
    override fun onCreateViewHolder(p: ViewGroup, t: Int) = VH(ItemCallBinding.inflate(LayoutInflater.from(p.context), p, false))
    override fun onBindViewHolder(h: VH, pos: Int) = h.bind(getItem(pos))
}

class RecentsFragment : Fragment() {
    private var _b: FragmentRecentsBinding? = null
    private val b get() = _b!!
    private val vm: RecentsViewModel by viewModels()
    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View { _b = FragmentRecentsBinding.inflate(i, c, false); return b.root }
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val adapter = CallAdapter(
            onCall = { (activity as? MainActivity)?.call(it.number) },
            onSms  = { (activity as? MainActivity)?.sms(it.number) },
            onLong = { e, v -> PopupMenu(requireContext(), v).apply {
                menu.add("📞 Rappeler"); menu.add("💬 SMS"); menu.add("➕ Ajouter"); menu.add("🚫 Bloquer"); menu.add("🗑️ Supprimer")
                setOnMenuItemClickListener { item -> when(item.title.toString()) {
                    "📞 Rappeler" -> (activity as? MainActivity)?.call(e.number)
                    "💬 SMS"      -> (activity as? MainActivity)?.sms(e.number)
                    "➕ Ajouter"  -> startActivity(android.content.Intent(android.content.Intent.ACTION_INSERT_OR_EDIT).apply {
                        type = android.provider.ContactsContract.Contacts.CONTENT_ITEM_TYPE
                        putExtra(android.provider.ContactsContract.Intents.Insert.PHONE, e.number)
                    })
                    "🚫 Bloquer"  -> vm.block(e.number)
                    "🗑️ Supprimer" -> vm.delete(e.id)
                }; true }; show() } }
        )
        b.rv.layoutManager = LinearLayoutManager(requireContext()); b.rv.adapter = adapter
        b.chipAll.setOnClickListener    { vm.setFilter(null) }
        b.chipMissed.setOnClickListener { vm.setFilter(CallType.MISSED) }
        b.chipIn.setOnClickListener     { vm.setFilter(CallType.INCOMING) }
        b.chipOut.setOnClickListener    { vm.setFilter(CallType.OUTGOING) }
        vm.entries.observe(viewLifecycleOwner) { adapter.submitList(it); b.tvEmpty.visibility = if (it.isEmpty()) View.VISIBLE else View.GONE }
        vm.loading.observe(viewLifecycleOwner) { b.progress.visibility = if (it) View.VISIBLE else View.GONE }
        vm.load()
    }
    override fun onDestroyView() { super.onDestroyView(); _b = null }
}

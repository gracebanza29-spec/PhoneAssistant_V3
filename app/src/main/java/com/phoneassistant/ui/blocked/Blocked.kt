package com.phoneassistant.ui.blocked

import android.app.Application
import android.os.Bundle
import android.view.*
import android.widget.*
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.*
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.phoneassistant.data.AppStorage
import com.phoneassistant.data.BlockedEntry
import com.phoneassistant.databinding.FragmentBlockedBinding
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class BlockedViewModel(app: Application) : AndroidViewModel(app) {
    private val storage = AppStorage(app)
    val list = MutableLiveData<List<BlockedEntry>>()
    fun load() = viewModelScope.launch { list.value = storage.getBlockedNumbers() }
    fun block(n: String) = viewModelScope.launch { storage.blockNumber(n, "Bloqué manuellement"); load() }
    fun unblock(n: String) = viewModelScope.launch { storage.unblockNumber(n); load() }
}

class BlockedFragment : Fragment() {
    private var _b: FragmentBlockedBinding? = null
    private val b get() = _b!!
    private val vm: BlockedViewModel by viewModels()
    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View { _b = FragmentBlockedBinding.inflate(i, c, false); return b.root }
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        vm.list.observe(viewLifecycleOwner) { numbers ->
            b.tvEmpty.visibility = if (numbers.isEmpty()) View.VISIBLE else View.GONE
            val sdf = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
            b.tvList.text = numbers.joinToString("\n\n") { n ->
                "🚫 ${n.number}\n${if(n.reason.isNotBlank()) "   Raison : ${n.reason}\n" else ""}   Bloqué le : ${sdf.format(Date(n.blockedAt))}"
            }
        }
        b.tvList.setOnLongClickListener {
            val numbers = vm.list.value ?: return@setOnLongClickListener true
            if (numbers.isEmpty()) return@setOnLongClickListener true
            val items = numbers.map { it.number }.toTypedArray()
            MaterialAlertDialogBuilder(requireContext()).setTitle("Débloquer un numéro")
                .setItems(items) { _, idx -> vm.unblock(numbers[idx].number) }.show()
            true
        }
        b.fab.setOnClickListener {
            val input = android.widget.EditText(requireContext()).apply { hint = "Ex: 0033123456789"; inputType = android.text.InputType.TYPE_CLASS_PHONE }
            MaterialAlertDialogBuilder(requireContext()).setTitle("🚫 Bloquer un numéro").setView(input)
                .setPositiveButton("Bloquer") { _, _ -> val n = input.text.toString().trim(); if (n.isNotEmpty()) { vm.block(n); Toast.makeText(requireContext(), "$n bloqué ✓", Toast.LENGTH_SHORT).show() } }
                .setNegativeButton("Annuler", null).show()
        }
        vm.load()
    }
    override fun onDestroyView() { super.onDestroyView(); _b = null }
}

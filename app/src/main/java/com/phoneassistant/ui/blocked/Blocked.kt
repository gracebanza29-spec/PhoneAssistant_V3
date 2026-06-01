package com.phoneassistant.ui.blocked

import android.app.Application
import android.os.Bundle
import android.view.*
import android.widget.*
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.*
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.phoneassistant.data.AppStorage
import com.phoneassistant.data.BlockedEntry
import com.phoneassistant.databinding.FragmentBlockedBinding
import com.phoneassistant.databinding.ItemBlockedBinding
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

class BlockedAdapter(
    private val onUnblock: (BlockedEntry) -> Unit
) : ListAdapter<BlockedEntry, BlockedAdapter.VH>(object : DiffUtil.ItemCallback<BlockedEntry>() {
    override fun areItemsTheSame(a: BlockedEntry, b: BlockedEntry) = a.normalized == b.normalized
    override fun areContentsTheSame(a: BlockedEntry, b: BlockedEntry) = a == b
}) {
    private val sdf = SimpleDateFormat("dd/MM/yyyy 'à' HH:mm", Locale.FRENCH)
    inner class VH(private val b: ItemBlockedBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(e: BlockedEntry) {
            b.tvNumber.text = e.number
            b.tvSub.text = buildString {
                if (e.reason.isNotBlank()) append(e.reason).append(" • ")
                append(sdf.format(Date(e.blockedAt)))
            }
            b.btnUnblock.setOnClickListener { onUnblock(e) }
        }
    }
    override fun onCreateViewHolder(p: ViewGroup, t: Int) = VH(ItemBlockedBinding.inflate(LayoutInflater.from(p.context), p, false))
    override fun onBindViewHolder(h: VH, pos: Int) = h.bind(getItem(pos))
}

class BlockedFragment : Fragment() {
    private var _b: FragmentBlockedBinding? = null
    private val b get() = _b!!
    private val vm: BlockedViewModel by viewModels()
    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View { _b = FragmentBlockedBinding.inflate(i, c, false); return b.root }
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val adapter = BlockedAdapter(onUnblock = { e -> confirmUnblock(e) })
        b.rv.layoutManager = LinearLayoutManager(requireContext())
        b.rv.adapter = adapter
        vm.list.observe(viewLifecycleOwner) { numbers ->
            adapter.submitList(numbers)
            b.tvEmpty.visibility = if (numbers.isEmpty()) View.VISIBLE else View.GONE
        }
        b.fab.setOnClickListener { promptBlock() }
        vm.load()
    }

    private fun confirmUnblock(e: BlockedEntry) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(com.phoneassistant.R.string.unblock_number))
            .setMessage(e.number)
            .setPositiveButton("Confirmer") { _, _ ->
                vm.unblock(e.number)
                Toast.makeText(requireContext(), "${e.number} débloqué", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun promptBlock() {
        val input = EditText(requireContext()).apply {
            hint = "Ex: 0033123456789"
            inputType = android.text.InputType.TYPE_CLASS_PHONE
        }
        val pad = (16 * resources.displayMetrics.density).toInt()
        val container = FrameLayout(requireContext()).apply { setPadding(pad, 0, pad, 0); addView(input) }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(com.phoneassistant.R.string.block_a_number))
            .setView(container)
            .setPositiveButton("Bloquer") { _, _ ->
                val n = input.text.toString().trim()
                if (n.isNotEmpty()) {
                    vm.block(n)
                    Toast.makeText(requireContext(), "$n bloqué", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    override fun onDestroyView() { super.onDestroyView(); _b = null }
}

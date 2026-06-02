package com.phoneassistant.ui.contacts

import android.app.Application
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.phoneassistant.MainActivity
import com.phoneassistant.R
import com.phoneassistant.data.AppStorage
import com.phoneassistant.data.CallerIdRepo
import com.phoneassistant.data.Contact
import com.phoneassistant.data.ContactsRepo
import com.phoneassistant.databinding.FragmentContactsBinding
import com.phoneassistant.databinding.FragmentDetailBinding
import com.phoneassistant.databinding.ItemContactBinding
import com.phoneassistant.ui.Avatars
import kotlinx.coroutines.launch

class ContactsViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = ContactsRepo(app)
    private var all = listOf<Contact>()
    private val _list = MutableLiveData<List<Contact>>()
    val list: LiveData<List<Contact>> = _list
    val loading = MutableLiveData(false)
    fun load() = viewModelScope.launch {
        loading.value = true
        try { all = repo.getAll(); _list.value = all } catch (_: Exception) { _list.value = emptyList() }
        finally { loading.value = false }
    }
    fun search(q: String) {
        _list.value = if (q.isBlank()) all else {
            val lq = q.lowercase()
            all.filter { c -> c.name.lowercase().contains(lq) || c.phones.any { it.number.contains(lq) } }
        }
    }
}

class ContactsAdapter(
    private val onCall: (Contact) -> Unit,
    private val onClick: (Contact) -> Unit
) : ListAdapter<Contact, ContactsAdapter.VH>(object : DiffUtil.ItemCallback<Contact>() {
    override fun areItemsTheSame(a: Contact, b: Contact) = a.id == b.id
    override fun areContentsTheSame(a: Contact, b: Contact) = a == b
}) {
    inner class VH(private val b: ItemContactBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(c: Contact) {
            b.tvName.text = c.name
            b.tvNumber.text = c.phones.firstOrNull()?.let { "${it.number} • ${it.type}" } ?: ""
            Avatars.bind(b.avatarBg, b.tvInitials, c.name)
            b.ivStar.visibility = if (c.isFavorite) View.VISIBLE else View.GONE
            b.btnCall.setOnClickListener { onCall(c) }
            b.root.setOnClickListener { onClick(c) }
        }
    }
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemContactBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))
}

class ContactsFragment : Fragment() {
    private var _b: FragmentContactsBinding? = null
    private val b get() = _b!!
    private val vm: ContactsViewModel by viewModels()
    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _b = FragmentContactsBinding.inflate(i, c, false); return b.root
    }
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val adapter = ContactsAdapter(
            onCall = { c -> c.phones.firstOrNull()?.let { (activity as? MainActivity)?.call(it.number) } },
            onClick = { c ->
                ContactDetailFragment().apply { arguments = Bundle().apply { putLong("id", c.id) } }
                    .also {
                        parentFragmentManager.beginTransaction()
                            .setCustomAnimations(
                                R.anim.slide_in_right, R.anim.slide_out_left,
                                R.anim.slide_in_left, R.anim.slide_out_right
                            )
                            .replace(id, it).addToBackStack(null).commit()
                    }
            }
        )
        b.rv.layoutManager = LinearLayoutManager(requireContext())
        b.rv.adapter = adapter
        b.search.setOnQueryTextListener(object : androidx.appcompat.widget.SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(q: String?) = false
            override fun onQueryTextChange(q: String?): Boolean { vm.search(q ?: ""); return true }
        })
        vm.list.observe(viewLifecycleOwner) { list ->
            adapter.submitList(list)
            b.tvCount.text = "${list.size} contact${if (list.size > 1) "s" else ""}"
            b.tvEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        }
        vm.loading.observe(viewLifecycleOwner) { b.progress.visibility = if (it) View.VISIBLE else View.GONE }
        vm.load()
    }
    override fun onDestroyView() { super.onDestroyView(); _b = null }
}

class ContactDetailViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = ContactsRepo(app); private val callerRepo = CallerIdRepo(app); private val storage = AppStorage(app)
    val contact = MutableLiveData<Contact?>(); val callerInfo = MutableLiveData<String?>()
    val note = MutableLiveData(""); val isFav = MutableLiveData(false); val isBlocked = MutableLiveData(false)
    fun load(id: Long) = viewModelScope.launch {
        val c = try { repo.getAll().find { it.id == id } } catch (_: Exception) { null }; contact.value = c
        note.value = storage.getNote(id); isFav.value = storage.isFavorite(id)
        c?.phones?.firstOrNull()?.let { p ->
            isBlocked.value = storage.isBlocked(p.number)
            callerInfo.value = callerRepo.identify(p.number)?.let { i ->
                listOfNotNull(i.carrier?.let{"Operateur: $it"}, i.lineType, i.location?.let{"Localisation: $it"}, if(i.isSpam)"SPAM" else null).joinToString("\n").takeIf{it.isNotBlank()}
            }
        }
    }
    fun saveNote(id: Long, text: String) = viewModelScope.launch { storage.saveNote(id, text); note.value = text }
    fun toggleFav(id: Long) = viewModelScope.launch { if(isFav.value==true){storage.removeFavorite(id);isFav.value=false}else{storage.addFavorite(id);isFav.value=true} }
    fun toggleBlock(number: String) = viewModelScope.launch { if(isBlocked.value==true){storage.unblockNumber(number);isBlocked.value=false}else{storage.blockNumber(number);isBlocked.value=true} }
}

class ContactDetailFragment : Fragment() {
    private var _b: FragmentDetailBinding? = null; private val b get() = _b!!; private val vm: ContactDetailViewModel by viewModels()
    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View { _b = FragmentDetailBinding.inflate(i, c, false); return b.root }
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val id = arguments?.getLong("id") ?: return; vm.load(id)
        vm.contact.observe(viewLifecycleOwner) { c -> c ?: return@observe
            b.tvName.text = c.name
            Avatars.bind(b.avatarBg, b.tvInitials, c.name)
            b.chipGroup.removeAllViews()
            c.phones.forEach { p -> b.chipGroup.addView(Chip(requireContext()).apply { text="${p.number} (${p.type})"; setOnClickListener{(activity as? MainActivity)?.call(p.number)} }) }
        }
        vm.callerInfo.observe(viewLifecycleOwner) { b.cardCallerInfo.visibility=if(it!=null)View.VISIBLE else View.GONE; b.tvCallerInfo.text=it }
        vm.note.observe(viewLifecycleOwner) { b.etNote.setText(it) }
        vm.isFav.observe(viewLifecycleOwner) { b.btnFav.text=if(it)"Retirer des favoris" else "Ajouter aux favoris" }
        vm.isBlocked.observe(viewLifecycleOwner) { b.btnBlock.text=if(it)"Debloquer" else "Bloquer ce numero" }
        b.btnSaveNote.setOnClickListener { vm.saveNote(id, b.etNote.text.toString()); Toast.makeText(requireContext(),"Note sauvegardee",Toast.LENGTH_SHORT).show() }
        b.btnFav.setOnClickListener { vm.toggleFav(id) }
        b.btnBlock.setOnClickListener {
            val number = vm.contact.value?.phones?.firstOrNull()?.number ?: return@setOnClickListener
            MaterialAlertDialogBuilder(requireContext()).setTitle(if(vm.isBlocked.value==true)"Debloquer ?" else "Bloquer ?").setMessage(number).setPositiveButton("Confirmer"){_,_->vm.toggleBlock(number)}.setNegativeButton("Annuler",null).show()
        }
    }
    override fun onDestroyView() { super.onDestroyView(); _b = null }
}

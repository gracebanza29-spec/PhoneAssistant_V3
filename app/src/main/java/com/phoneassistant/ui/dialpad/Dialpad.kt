package com.phoneassistant.ui.dialpad

import android.app.Application
import android.os.Bundle
import android.view.*
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.*
import androidx.recyclerview.widget.*
import com.phoneassistant.MainActivity
import com.phoneassistant.data.Contact
import com.phoneassistant.data.ContactsRepo
import com.phoneassistant.databinding.FragmentDialpadBinding
import com.phoneassistant.databinding.ItemSuggestionBinding
import com.phoneassistant.ui.Avatars
import kotlinx.coroutines.launch

class DialpadViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = ContactsRepo(app)
    private var all = listOf<Contact>()
    val number = MutableLiveData("")
    private val _suggestions = MutableLiveData<List<Contact>>(emptyList())
    val suggestions: LiveData<List<Contact>> = _suggestions
    private val T9 = mapOf('2' to "abcABC",'3' to "defDEF",'4' to "ghiGHI",'5' to "jklJKL",
        '6' to "mnoMNO",'7' to "pqrsPQRS",'8' to "tuvTUV",'9' to "wxyzWXYZ")
    init { viewModelScope.launch { try { all = repo.getAll() } catch (_: Exception) {} } }
    fun type(d: String) { number.value = (number.value ?: "") + d; search() }
    fun back()          { number.value = number.value?.dropLast(1) ?: ""; search() }
    fun clear()         { number.value = ""; _suggestions.value = emptyList() }
    fun set(n: String)  { number.value = n; search() }
    private fun search() {
        val q = number.value ?: ""; if (q.length < 2) { _suggestions.value = emptyList(); return }
        viewModelScope.launch {
            _suggestions.value = all.filter { c ->
                c.phones.any { it.number.contains(q) } || matchT9(c.name, q)
            }.take(6)
        }
    }
    private fun matchT9(name: String, digits: String): Boolean {
        val letters = name.filter { it.isLetter() }
        if (letters.length < digits.length) return false
        for (i in digits.indices) {
            val l = letters.getOrNull(i) ?: return false
            if (l !in (T9[digits[i]] ?: return false)) return false
        }
        return true
    }
}

class SuggestionsAdapter(private val onClick: (Contact) -> Unit)
    : ListAdapter<Contact, SuggestionsAdapter.VH>(object : DiffUtil.ItemCallback<Contact>() {
    override fun areItemsTheSame(a: Contact, b: Contact) = a.id == b.id
    override fun areContentsTheSame(a: Contact, b: Contact) = a == b
}) {
    inner class VH(private val b: ItemSuggestionBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(c: Contact) {
            b.tvName.text = c.name
            b.tvNumber.text = c.phones.firstOrNull()?.number ?: ""
            b.tvType.text = c.phones.firstOrNull()?.type ?: ""
            Avatars.bind(b.avatarBg, b.tvInitials, c.name)
            b.root.setOnClickListener { onClick(c) }
        }
    }
    override fun onCreateViewHolder(p: ViewGroup, t: Int) = VH(ItemSuggestionBinding.inflate(LayoutInflater.from(p.context), p, false))
    override fun onBindViewHolder(h: VH, pos: Int) = h.bind(getItem(pos))
}

class DialpadFragment : Fragment() {
    private var _b: FragmentDialpadBinding? = null
    private val b get() = _b!!
    private val vm: DialpadViewModel by viewModels()

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _b = FragmentDialpadBinding.inflate(i, c, false); return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        arguments?.getString("prefill")?.let { vm.set(it) }
        val adapter = SuggestionsAdapter { c ->
            c.phones.firstOrNull()?.let { (activity as? MainActivity)?.call(it.number) }
        }
        b.rvSuggestions.layoutManager = LinearLayoutManager(requireContext())
        b.rvSuggestions.adapter = adapter

        // Connecte chaque touche au ViewModel + retour haptique
        mapOf(
            b.btn0 to "0", b.btn1 to "1", b.btn2 to "2", b.btn3 to "3", b.btn4 to "4",
            b.btn5 to "5", b.btn6 to "6", b.btn7 to "7", b.btn8 to "8", b.btn9 to "9",
            b.btnStar to "*", b.btnHash to "#"
        ).forEach { (btn, d) ->
            btn.setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                vm.type(d)
            }
            // Appui long sur 0 → insère "+"
            if (d == "0") btn.setOnLongClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                vm.type("+"); true
            }
        }

        b.btnDel.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); vm.back()
        }
        b.btnDel.setOnLongClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS); vm.clear(); true
        }
        b.btnCall.setOnClickListener {
            // Passe le numéro brut (sans espaces de formatage) à l'appel téléphonique
            vm.number.value?.takeIf { it.isNotBlank() }
                ?.let { (activity as? MainActivity)?.call(it) }
        }
        b.btnSms.setOnClickListener {
            vm.number.value?.takeIf { it.isNotBlank() }
                ?.let { (activity as? MainActivity)?.sms(it) }
        }

        vm.number.observe(viewLifecycleOwner) { n ->
            b.tvNumber.text = formatNumber(n)
            b.btnDel.visibility = if (n.isNotEmpty()) View.VISIBLE else View.INVISIBLE
            // INVISIBLE (pas GONE) pour éviter tout décalage de layout
            b.btnSms.visibility = if (n.isNotEmpty()) View.VISIBLE else View.INVISIBLE
        }
        vm.suggestions.observe(viewLifecycleOwner) { list ->
            adapter.submitList(list)
            b.rvSuggestions.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    /**
     * Formate le numéro saisi pour l'affichage (ne change pas la valeur stockée).
     * - International (+XXX…) : +243 81 234 5678
     * - Local 10 chiffres (0XXXXXXXXX) : 0XX XXX XXXX
     * - Autres longueurs : affiché tel quel (auto-resize gère le débordement)
     */
    private fun formatNumber(n: String): String {
        if (n.isEmpty()) return ""
        // Numéro international
        if (n.startsWith("+")) {
            val digits = n.removePrefix("+").filter { it.isDigit() }
            return when {
                digits.length <= 3  -> "+$digits"
                digits.length <= 5  -> "+${digits.substring(0,3)} ${digits.substring(3)}"
                digits.length <= 8  -> "+${digits.substring(0,3)} ${digits.substring(3,5)} ${digits.substring(5)}"
                digits.length <= 12 -> "+${digits.substring(0,3)} ${digits.substring(3,5)} ${digits.substring(5,8)} ${digits.substring(8)}"
                else                -> n
            }
        }
        // Numéro local 10 chiffres (format Congo : 0XX XXX XXXX)
        val digits = n.filter { it.isDigit() }
        return when (digits.length) {
            10   -> "${digits.substring(0,3)} ${digits.substring(3,6)} ${digits.substring(6)}"
            9    -> "${digits.substring(0,2)} ${digits.substring(2,5)} ${digits.substring(5)}"
            else -> n  // Saisie partielle — affichage brut, l'auto-resize gère la taille
        }
    }

    override fun onDestroyView() { super.onDestroyView(); _b = null }
}

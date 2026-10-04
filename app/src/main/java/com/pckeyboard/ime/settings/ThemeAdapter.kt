package com.pckeyboard.ime.settings

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.pckeyboard.ime.databinding.ItemThemeBinding
import com.pckeyboard.ime.R
import com.pckeyboard.ime.theme.KeyboardTheme
import com.pckeyboard.ime.theme.Themes

class ThemeAdapter(
    private val onSelect: (KeyboardTheme) -> Unit,
    private val onEdit: (KeyboardTheme) -> Unit,
    private val onDelete: (KeyboardTheme) -> Unit,
    initialSelectedId: String
) : RecyclerView.Adapter<ThemeAdapter.VH>() {

    private val items = mutableListOf<KeyboardTheme>()
    var selectedId: String = initialSelectedId

    fun submit(list: List<KeyboardTheme>) {
        items.clear(); items.addAll(list); notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemThemeBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val t = items[position]
        holder.bind(t)
    }

    inner class VH(private val b: ItemThemeBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(t: KeyboardTheme) {
            b.themeName.text = when (t.id) {
                "light" -> b.root.context.getString(R.string.theme_magic_light)
                "dark" -> b.root.context.getString(R.string.theme_magic_dark)
                "black" -> b.root.context.getString(R.string.theme_amoled)
                else -> t.name
            }
            b.themeSubtitle.setText(if (t.isDark) R.string.theme_dark else R.string.theme_light)
            b.swatchBackground.setBackgroundColor(t.backgroundColor)
            b.swatchKey.setBackgroundColor(t.keyBackgroundColor)
            b.swatchModifier.setBackgroundColor(t.modifierKeyColor)
            b.swatchAccent.setBackgroundColor(t.accentColor)
            b.selectedIndicator.visibility =
                if (t.id == selectedId) android.view.View.VISIBLE
                else android.view.View.INVISIBLE
            b.root.setOnClickListener { onSelect(t) }
            val isBuiltIn = Themes.builtIn.any { it.id == t.id }
            b.btnEdit.visibility = if (isBuiltIn) android.view.View.GONE else android.view.View.VISIBLE
            b.btnDelete.visibility = if (isBuiltIn) android.view.View.GONE else android.view.View.VISIBLE
            b.btnEdit.setOnClickListener { onEdit(t) }
            b.btnDelete.setOnClickListener { onDelete(t) }
        }
    }
}

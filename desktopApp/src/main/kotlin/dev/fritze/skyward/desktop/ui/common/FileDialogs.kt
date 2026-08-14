package dev.fritze.skyward.desktop.ui.common

import java.io.File
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

/**
 * §12's export/import file picking. Swing's `JFileChooser` rather than AWT's
 * `FileDialog`: it needs no parent frame (Compose owns the window, and
 * reaching for its AWT peer just to open a dialog is not worth it) and it
 * behaves identically inside and outside the Flatpak sandbox, where the
 * granted `--filesystem` scope is what actually limits where the user can go.
 */
object SyncFileDialogs {

    fun chooseExportTarget(defaultName: String): File? {
        val chooser = JFileChooser().apply {
            dialogTitle = "Export Skyward settings"
            selectedFile = File(defaultName)
            fileFilter = JSON_FILTER
        }
        if (chooser.showSaveDialog(null) != JFileChooser.APPROVE_OPTION) return null
        val chosen = chooser.selectedFile ?: return null
        // A user who typed a bare name gets the extension the format expects,
        // matching what the Android SAF picker does with its MIME type.
        // `parentFile` is null for a bare relative name, and `File(null, name)`
        // would resolve it against the process's working directory rather than
        // the directory the chooser is actually showing.
        if (chosen.name.contains('.')) return chosen
        val parent = chosen.parentFile ?: chooser.currentDirectory
        return if (parent == null) File("${chosen.name}.json") else File(parent, "${chosen.name}.json")
    }

    fun chooseImportSource(): File? {
        val chooser = JFileChooser().apply {
            dialogTitle = "Import Skyward settings"
            fileFilter = JSON_FILTER
        }
        if (chooser.showOpenDialog(null) != JFileChooser.APPROVE_OPTION) return null
        return chooser.selectedFile?.takeIf { it.isFile }
    }

    private val JSON_FILTER = FileNameExtensionFilter("Skyward export (*.json)", "json")
}

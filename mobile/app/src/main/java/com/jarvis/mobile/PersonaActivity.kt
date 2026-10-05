package com.jarvis.mobile

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import java.io.File

/**
 * Persona editor — where you customize the ENTIRE AI with a Markdown file. Whatever you
 * write here is folded in front of JARVIS's system prompt for every decision and reply.
 * Import a `.md`, edit it, save — no rebuild needed.
 */
class PersonaActivity : Activity() {
    private lateinit var edt: EditText
    private val personaFile by lazy { File(filesDir, "persona.md") }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_persona)
        edt = findViewById(R.id.edtPersona)
        edt.setText(if (personaFile.exists()) personaFile.readText() else "")

        findViewById<Button>(R.id.btnSave).setOnClickListener { save() }
        findViewById<Button>(R.id.btnReset).setOnClickListener {
            edt.setText("")
            personaFile.delete()
            JarvisConfig.persona = ""
            toast("Reset to the built-in default")
        }
        findViewById<Button>(R.id.btnImport).setOnClickListener { importMd() }
    }

    private fun save() {
        val text = edt.text.toString()
        if (text.isBlank()) personaFile.delete() else personaFile.writeText(text)
        JarvisConfig.persona = text.trim()
        toast("Persona saved")
        finish()
    }

    private fun importMd() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("text/markdown", "text/plain", "text/*"))
        }
        @Suppress("DEPRECATION")
        startActivityForResult(i, REQ_IMPORT)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_IMPORT && resultCode == Activity.RESULT_OK) {
            val uri = data?.data ?: return
            val text = try {
                contentResolver.openInputStream(uri)?.bufferedReader()?.readText() ?: ""
            } catch (e: Exception) { "" }
            if (text.isNotBlank()) edt.setText(text) else toast("Couldn't read that file")
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    companion object { const val REQ_IMPORT = 2002 }
}

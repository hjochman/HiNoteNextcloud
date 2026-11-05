package local.papernotes

import android.app.Activity
import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import local.papernotes.editor.EditorActivity
import local.papernotes.home.DateLabels
import local.papernotes.home.Entry
import local.papernotes.home.FolderItem
import local.papernotes.home.GridMetrics
import local.papernotes.home.HomeModel
import local.papernotes.home.HomeTiles
import local.papernotes.home.Pagination
import local.papernotes.home.PreviewBinder
import local.papernotes.home.PreviewLoader
import local.papernotes.home.TileActions
import local.papernotes.model.NoteFactory
import local.papernotes.storage.FolderPath
import local.papernotes.storage.NoteRepository
import local.papernotes.storage.NoteSummary
import local.papernotes.storage.NoteTitles
import local.papernotes.storage.PnoteError
import local.papernotes.storage.PnoteResult
import local.papernotes.storage.NoteOrdering
import local.papernotes.storage.SortOrder
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Startseite: Ordner und Notizen als Raster oder Liste, Blättern statt Scrollen (E-Ink), Suche, Notiz- und Ordnermenü.
 * Dateizugriffe laufen nie im UI-Thread; Titel und Pfade werden nicht geloggt.
 */
class MainActivity : Activity() {
    private val io: ExecutorService = Executors.newSingleThreadExecutor()

    private lateinit var buttonBack: ImageButton
    private lateinit var textTitle: TextView
    private lateinit var editSearch: EditText
    private lateinit var buttonNew: ImageButton
    private lateinit var buttonSearch: ImageButton
    private lateinit var buttonViewMode: ImageButton
    private lateinit var buttonMore: ImageButton
    private lateinit var textCount: TextView
    private lateinit var contentArea: View
    private lateinit var pageRows: LinearLayout
    private lateinit var textEmpty: TextView
    private lateinit var buttonPrevPage: View
    private lateinit var buttonNextPage: View
    private lateinit var textPageNumber: TextView

    private var repo: NoteRepository? = null
    private var loader: PreviewLoader? = null
    private var binder: PreviewBinder? = null

    private var currentFolder = ""
    private var listMode = false
    private var sortOrder = SortOrder.DEFAULT
    private var page = 0
    private var searchMode = false
    private var query = ""

    private var loaded = false
    private var loadToken = 0
    private var entries: List<Entry> = emptyList()
    private var totalCount = 0
    private var emptyMessage = R.string.notes_empty
    private var layout: List<List<List<Int>>> = emptyList()
    private var permissionAsked = false

    /** [icon] = 0: kein Symbol, aber gleiche Einrückung (z. B. nicht gewählte Sortierung). */
    private class MenuEntry(val icon: Int, val label: String, val enabled: Boolean = true, val action: () -> Unit)

    private val tileActions = object : TileActions {
        override fun openNote(id: String) {
            startActivity(EditorActivity.intent(this@MainActivity, id))
        }

        override fun openFolder(path: String) {
            currentFolder = path
            page = 0
            load()
        }

        override fun newQuickNote() = createNote()

        override fun noteMenu(anchor: View, note: NoteSummary) = showNoteMenu(anchor, note)

        override fun folderMenu(anchor: View, folder: FolderItem) = showFolderMenu(anchor, folder)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        buttonBack = findViewById(R.id.buttonBack)
        textTitle = findViewById(R.id.textTitle)
        editSearch = findViewById(R.id.editSearch)
        buttonNew = findViewById(R.id.buttonNew)
        buttonSearch = findViewById(R.id.buttonSearch)
        buttonViewMode = findViewById(R.id.buttonViewMode)
        buttonMore = findViewById(R.id.buttonMore)
        textCount = findViewById(R.id.textCount)
        contentArea = findViewById(R.id.contentArea)
        pageRows = findViewById(R.id.pageRows)
        textEmpty = findViewById(R.id.textEmpty)
        buttonPrevPage = findViewById(R.id.buttonPrevPage)
        buttonNextPage = findViewById(R.id.buttonNextPage)
        textPageNumber = findViewById(R.id.textPageNumber)

        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        listMode = prefs.getBoolean(KEY_LIST_MODE, false)
        sortOrder = SortOrder.parse(prefs.getString(KEY_SORT, null))
        if (savedInstanceState != null) {
            currentFolder = savedInstanceState.getString(STATE_FOLDER, "").orEmpty().takeIf { FolderPath.isValid(it) }.orEmpty()
            page = savedInstanceState.getInt(STATE_PAGE, 0)
            searchMode = savedInstanceState.getBoolean(STATE_SEARCH, false)
            query = savedInstanceState.getString(STATE_QUERY, "").orEmpty().take(NoteRepository.MAX_QUERY_LENGTH)
        }

        editSearch.filters = arrayOf(InputFilter.LengthFilter(NoteRepository.MAX_QUERY_LENGTH))
        editSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (!searchMode) return
                query = s?.toString().orEmpty().filterNot { it.isISOControl() }.trim().take(NoteRepository.MAX_QUERY_LENGTH)
                page = 0
                load()
            }
        })
        editSearch.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                hideKeyboard(v)
                true
            } else {
                false
            }
        }

        buttonBack.setOnClickListener { goUp() }
        buttonNew.setOnClickListener { showNewMenu(it) }
        buttonMore.setOnClickListener { showMoreMenu(it) }
        buttonSearch.setOnClickListener { if (searchMode) closeSearch() else openSearch() }
        buttonViewMode.setOnClickListener { toggleViewMode() }
        buttonPrevPage.setOnClickListener {
            page = Pagination.previous(page, layout.size)
            render()
        }
        buttonNextPage.setOnClickListener {
            page = Pagination.next(page, layout.size)
            render()
        }
        contentArea.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
            if (r - l != or - ol || b - t != ob - ot) contentArea.post { render() }
        }
        applySearchUi()
        updateViewModeButton()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_FOLDER, currentFolder)
        outState.putInt(STATE_PAGE, page)
        outState.putBoolean(STATE_SEARCH, searchMode)
        outState.putString(STATE_QUERY, query)
    }

    override fun onResume() {
        super.onResume()
        if (NotesLocation.hasAccess(this)) {
            ensureRepo()
            load()
        } else if (!permissionAsked) {
            permissionAsked = true
            requestPermissions(arrayOf(NotesLocation.PERMISSION), REQUEST_STORAGE)
        } else {
            entries = emptyList()
            totalCount = 0
            emptyMessage = R.string.error_no_storage
            loaded = true
            render()
        }
    }

    @Deprecated("Activity-API ohne AndroidX; hier bewusst genutzt.")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_STORAGE) onResume()
    }

    @Deprecated("Activity-API ohne AndroidX; hier bewusst genutzt.")
    override fun onBackPressed() {
        when {
            searchMode -> closeSearch()
            currentFolder.isNotEmpty() -> goUp()
            else -> @Suppress("DEPRECATION") super.onBackPressed()
        }
    }

    override fun onDestroy() {
        loadToken++
        io.shutdown()
        loader?.shutdown()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- Laden und Darstellen

    private fun ensureRepo() {
        if (repo != null) return
        val root = NotesLocation.root(this)
        repo = NoteRepository(root)
        val l = PreviewLoader(this, root)
        l.onReady = { id -> binder?.update(id) }
        loader = l
        binder = PreviewBinder(l)
    }

    private fun load() {
        val r = repo ?: return
        if (isDestroyed) return
        val token = ++loadToken
        val folder = currentFolder
        val order = sortOrder
        val q = if (searchMode) query else ""
        io.execute {
            var list: List<Entry>? = null
            var count = 0
            if (q.isNotEmpty()) {
                (r.search(q) as? PnoteResult.Ok)?.value?.let { hits ->
                    list = NoteOrdering.sort(hits, order).map { Entry.Note(it) }
                    count = hits.size
                }
            } else {
                (HomeModel.load(r, folder, order) as? PnoteResult.Ok)?.value?.let { c ->
                    list = HomeModel.entries(c, folder.isNotEmpty())
                    count = c.totalCount
                }
            }
            runOnUiThread {
                if (isDestroyed || token != loadToken) return@runOnUiThread
                val result = list
                if (result == null) {
                    toast(R.string.error_action_failed)
                    entries = emptyList()
                    totalCount = 0
                } else {
                    entries = result
                    totalCount = count
                }
                emptyMessage = if (q.isNotEmpty()) R.string.search_empty else R.string.notes_empty
                loaded = true
                render()
            }
        }
    }

    private fun render() {
        if (!loaded) return
        updateHeader()
        val w = contentArea.width
        val h = contentArea.height
        val b = binder
        if (w <= 0 || h <= 0 || b == null) {
            if (b == null) showEmptyOnly()
            return
        }
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val metrics = GridMetrics.compute(w, resources.displayMetrics.density, listMode, landscape)
        layout = HomeModel.pageLayout(entries, metrics, h)
        page = Pagination.clamp(page, layout.size)
        b.clear()
        pageRows.removeAllViews()
        if (entries.isEmpty()) {
            textEmpty.setText(emptyMessage)
            textEmpty.visibility = View.VISIBLE
        } else {
            textEmpty.visibility = View.GONE
            val dates = DateLabels(Locale.getDefault(), TimeZone.getDefault(), System::currentTimeMillis) { loc, skeleton ->
                android.text.format.DateFormat.getBestDateTimePattern(loc, skeleton)
            }
            val tiles = HomeTiles(this, metrics, b, dates, tileActions)
            layout[page].forEachIndexed { i, row -> pageRows.addView(tiles.buildRow(entries, row, i == 0)) }
        }
        updatePager()
    }

    /** Ohne Speicherzugriff gibt es keine Vorschau-Infrastruktur: nur die Meldung zeigen. */
    private fun showEmptyOnly() {
        pageRows.removeAllViews()
        layout = emptyList()
        textEmpty.setText(emptyMessage)
        textEmpty.visibility = View.VISIBLE
        updatePager()
    }

    private fun updatePager() {
        textPageNumber.text = Pagination.label(page, layout.size)
        buttonPrevPage.isEnabled = Pagination.hasPrevious(page)
        buttonNextPage.isEnabled = Pagination.hasNext(page, layout.size)
    }

    private fun updateHeader() {
        textTitle.text = if (currentFolder.isEmpty()) getString(R.string.home_all_notes) else HomeModel.lastSegment(currentFolder)
        textCount.text = if (loaded) resources.getQuantityString(R.plurals.notes_count, totalCount, totalCount) else ""
        buttonBack.visibility = if (currentFolder.isNotEmpty() && !searchMode) View.VISIBLE else View.GONE
    }

    private fun goUp() {
        if (currentFolder.isEmpty()) return
        currentFolder = HomeModel.parent(currentFolder)
        page = 0
        load()
    }

    // ---------------------------------------------------------------- Suche und Ansicht

    private fun openSearch() {
        searchMode = true
        query = ""
        page = 0
        applySearchUi()
        editSearch.requestFocus()
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(editSearch, InputMethodManager.SHOW_IMPLICIT)
        load()
    }

    private fun closeSearch() {
        searchMode = false
        query = ""
        hideKeyboard(editSearch)
        editSearch.setText("")
        page = 0
        applySearchUi()
        load()
    }

    private fun applySearchUi() {
        editSearch.visibility = if (searchMode) View.VISIBLE else View.GONE
        textTitle.visibility = if (searchMode) View.GONE else View.VISIBLE
        buttonBack.visibility = if (currentFolder.isNotEmpty() && !searchMode) View.VISIBLE else View.GONE
        buttonSearch.setImageResource(if (searchMode) R.drawable.ic_close else R.drawable.ic_search)
        buttonSearch.contentDescription = getString(if (searchMode) R.string.search_close else R.string.search)
    }

    private fun hideKeyboard(v: View) {
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(v.windowToken, 0)
    }

    private fun toggleViewMode() {
        listMode = !listMode
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_LIST_MODE, listMode).apply()
        page = 0
        updateViewModeButton()
        render()
    }

    /** Das Symbol zeigt die Ansicht, zu der ein Tipp wechselt. */
    private fun updateViewModeButton() {
        buttonViewMode.setImageResource(if (listMode) R.drawable.ic_view_grid else R.drawable.ic_view_list)
        buttonViewMode.contentDescription = getString(if (listMode) R.string.view_grid else R.string.view_list)
    }

    // ---------------------------------------------------------------- Menüs

    private fun showNewMenu(anchor: View) = showMenu(
        anchor,
        listOf(
            MenuEntry(R.drawable.ic_note_add, getString(R.string.new_note)) { createNote() },
            MenuEntry(R.drawable.ic_folder_add, getString(R.string.new_folder)) { askNewFolder() },
        ),
    )

    private fun showMoreMenu(anchor: View) = showMenu(
        anchor,
        listOf(
            MenuEntry(R.drawable.ic_folder_add, getString(R.string.new_folder)) { askNewFolder() },
            MenuEntry(R.drawable.ic_sort, getString(R.string.sort_by)) { showSortMenu(anchor) },
        ),
    )

    /** Sortierung der Notizen; der Haken zeigt die aktuelle Wahl. Gilt für Ordneransicht und Suche, fixierte Notizen bleiben oben. */
    private fun showSortMenu(anchor: View) {
        val labels = mapOf(
            SortOrder.MODIFIED_DESC to R.string.sort_modified_desc,
            SortOrder.MODIFIED_ASC to R.string.sort_modified_asc,
            SortOrder.CREATED_DESC to R.string.sort_created_desc,
            SortOrder.TITLE_ASC to R.string.sort_title_asc,
            SortOrder.TITLE_DESC to R.string.sort_title_desc,
        )
        showMenu(
            anchor,
            SortOrder.values().map { order ->
                MenuEntry(if (order == sortOrder) R.drawable.ic_check else 0, getString(labels.getValue(order))) { setSortOrder(order) }
            },
        )
    }

    private fun setSortOrder(order: SortOrder) {
        if (order == sortOrder) return
        sortOrder = order
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_SORT, order.name).apply()
        page = 0
        load()
    }

    private fun showNoteMenu(anchor: View, note: NoteSummary) = showMenu(
        anchor,
        listOf(
            MenuEntry(R.drawable.ic_rename, getString(R.string.action_rename)) { askRenameNote(note) },
            MenuEntry(
                if (note.favorite) R.drawable.ic_star_filled else R.drawable.ic_star,
                getString(if (note.favorite) R.string.action_fav_remove else R.string.action_fav_add),
            ) { runRepo { it.setFavorite(note.id, !note.favorite) } },
            MenuEntry(R.drawable.ic_pin_top, getString(if (note.pinned) R.string.action_unpin else R.string.action_pin)) {
                runRepo { it.setPinned(note.id, !note.pinned) }
            },
            MenuEntry(R.drawable.ic_copy, getString(R.string.action_copy)) {
                val title = NoteTitles.copyTitle(note.title) { getString(R.string.copy_title_format, it) }
                runRepo { it.copy(note.id, title) }
            },
            MenuEntry(R.drawable.ic_move, getString(R.string.action_move)) { askMoveNote(note) },
            MenuEntry(R.drawable.ic_share, getString(R.string.action_share)) { shareNote(note) },
            MenuEntry(R.drawable.ic_export, getString(R.string.action_export), enabled = false) { },
            MenuEntry(R.drawable.ic_trash, getString(R.string.delete)) { askDeleteNote(note) },
        ),
    )

    private fun showFolderMenu(anchor: View, folder: FolderItem) = showMenu(
        anchor,
        listOf(
            MenuEntry(R.drawable.ic_rename, getString(R.string.action_rename)) { askRenameFolder(folder) },
            MenuEntry(R.drawable.ic_trash, getString(R.string.delete)) { askDeleteFolder(folder) },
        ),
    )

    /**
     * Popup wie in HiNote: weiß, schwarzer Rand, ohne Animation und Schatten, Symbol vor jedem Eintrag.
     * Die Breite entspricht dem breitesten Eintrag (vorab gemessen), sonst füllen die Trennlinien die ganze Bildschirmbreite.
     */
    private fun showMenu(anchor: View, items: List<MenuEntry>) {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = getDrawable(R.drawable.bg_dialog)
            setPadding(dp(2), dp(2), dp(2), dp(2))
        }
        lateinit var popup: PopupWindow
        items.forEachIndexed { i, item ->
            if (i > 0) {
                content.addView(
                    View(this).apply { setBackgroundColor(getColor(R.color.line_gray)) },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)),
                )
            }
            content.addView(menuRow(item) {
                popup.dismiss()
                item.action()
            })
        }
        val unspecified = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        content.measure(unspecified, unspecified)
        val width = minOf(content.measuredWidth, resources.displayMetrics.widthPixels - dp(16))
        popup = PopupWindow(content, width, ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            elevation = 0f
            animationStyle = 0
            isOutsideTouchable = true
        }
        popup.showAsDropDown(anchor, 0, 0, Gravity.END)
    }

    private fun menuRow(item: MenuEntry, onClick: () -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(56)
            setPadding(dp(16), 0, dp(16), 0)
            background = getDrawable(R.drawable.bg_tool)
            isEnabled = item.enabled
            isClickable = item.enabled
            isFocusable = item.enabled
            if (item.enabled) setOnClickListener { onClick() }
        }
        row.addView(
            ImageView(this).apply {
                if (item.icon != 0) setImageResource(item.icon)
                imageTintList = getColorStateList(R.color.icon_tint)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                isEnabled = item.enabled
            },
            LinearLayout.LayoutParams(dp(24), dp(24)),
        )
        row.addView(
            TextView(this).apply {
                text = item.label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                setTextColor(getColorStateList(R.color.text_tint))
                isEnabled = item.enabled
                setPadding(dp(16), 0, 0, 0)
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        return row
    }

    // ---------------------------------------------------------------- Aktionen

    /** Führt [block] im Hintergrund aus, meldet Fehler und lädt danach neu. */
    private fun runRepo(onError: ((PnoteError) -> Int)? = null, block: (NoteRepository) -> PnoteResult<*>) {
        val r = repo ?: return
        if (isDestroyed) return
        io.execute {
            val error = (block(r) as? PnoteResult.Err)?.error
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                if (error != null) toast(onError?.invoke(error) ?: R.string.error_action_failed)
                load()
            }
        }
    }

    private fun createNote() {
        val r = repo
        if (r == null || !NotesLocation.hasAccess(this)) {
            toast(R.string.error_no_storage)
            return
        }
        val folder = currentFolder
        val title = getString(R.string.new_note_title) + " " + DateFormat.getDateInstance(DateFormat.SHORT).format(Date())
        io.execute {
            val note = NoteFactory.newNote(title, folder)
            val ok = r.save(note) is PnoteResult.Ok
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                if (ok) startActivity(EditorActivity.intent(this, note.id)) else toast(R.string.error_create)
            }
        }
    }

    private fun askNewFolder() {
        nameDialog(
            title = getString(R.string.dialog_new_folder),
            initial = "",
            maxLen = FolderPath.MAX_SEGMENT_LENGTH,
            errorRes = R.string.error_folder_name_invalid,
            normalize = { it.trim() },
            isValid = { FolderPath.isValidName(it) },
        ) { name ->
            val path = if (currentFolder.isEmpty()) name else "$currentFolder/$name"
            runRepo(onError = ::folderError) { it.createFolder(path) }
        }
    }

    private fun askRenameFolder(folder: FolderItem) {
        nameDialog(
            title = getString(R.string.dialog_rename_folder),
            initial = folder.name,
            maxLen = FolderPath.MAX_SEGMENT_LENGTH,
            errorRes = R.string.error_folder_name_invalid,
            normalize = { it.trim() },
            isValid = { FolderPath.isValidName(it) },
        ) { name ->
            if (name != folder.name) runRepo(onError = ::folderError) { it.renameFolder(folder.path, name) }
        }
    }

    private fun askDeleteFolder(folder: FolderItem) {
        if (folder.noteCount > 0) {
            toast(R.string.error_folder_not_empty, long = true)
            return
        }
        openDialog(getString(R.string.confirm_delete_folder_title), getString(R.string.delete), {
            runRepo(onError = ::folderError) { it.deleteFolder(folder.path) }
            true
        }) { body ->
            body.addView(messageText(getString(R.string.confirm_delete_folder_message, folder.name)))
        }
    }

    private fun askRenameNote(note: NoteSummary) {
        nameDialog(
            title = getString(R.string.dialog_rename_note),
            initial = note.title,
            maxLen = NoteTitles.MAX_LENGTH,
            errorRes = R.string.error_title_invalid,
            normalize = { NoteTitles.sanitize(it) },
            isValid = { NoteTitles.isValid(it) },
        ) { title ->
            runRepo { it.rename(note.id, title) }
        }
    }

    private fun askDeleteNote(note: NoteSummary) {
        openDialog(getString(R.string.confirm_delete_note_title), getString(R.string.delete), {
            runRepo { it.delete(note.id) }
            loader?.forget(note.id)
            true
        }) { body ->
            body.addView(messageText(getString(R.string.confirm_delete_note_message, note.title)))
        }
    }

    private fun askMoveNote(note: NoteSummary) {
        val r = repo ?: return
        io.execute {
            val folders = (r.listAllFolders() as? PnoteResult.Ok)?.value.orEmpty()
            runOnUiThread { if (!isDestroyed) showMoveDialog(note, folders) }
        }
    }

    private fun showMoveDialog(note: NoteSummary, folders: List<String>) {
        val targets = listOf("") + folders
        var dialog: Dialog? = null
        dialog = openDialog(getString(R.string.dialog_move), null) { body ->
            val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            targets.forEach { path ->
                val here = path == note.folderPath
                list.addView(
                    TextView(this).apply {
                        text = if (path.isEmpty()) getString(R.string.home_all_notes) else path
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                        setTextColor(getColorStateList(R.color.text_tint))
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(dp(8), 0, dp(8), 0)
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                        background = getDrawable(R.drawable.bg_tool)
                        isEnabled = !here
                        isClickable = !here
                        isFocusable = !here
                        if (!here) {
                            setOnClickListener {
                                dialog?.dismiss()
                                runRepo { it.move(note.id, path) }
                            }
                        }
                    },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)),
                )
            }
            val maxHeight = resources.displayMetrics.heightPixels / 2
            val scroll = ScrollView(this).apply { addView(list) }
            body.addView(
                scroll,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, minOf(dp(52) * targets.size, maxHeight)).also {
                    it.topMargin = dp(8)
                },
            )
        }
    }

    private fun shareNote(note: NoteSummary) {
        val r = repo ?: return
        io.execute {
            val shared = prepareShareFile(r, note)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                if (shared == null) {
                    toast(R.string.error_share)
                    return@runOnUiThread
                }
                try {
                    val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", shared)
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "application/octet-stream"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        clipData = ClipData.newRawUri("", uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    val chooser = Intent.createChooser(send, getString(R.string.share_chooser))
                    chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    startActivity(chooser)
                } catch (e: ActivityNotFoundException) {
                    toast(R.string.error_share)
                } catch (e: IllegalArgumentException) {
                    toast(R.string.error_share)
                }
            }
        }
    }

    /** Kopiert die Notizdatei mit unbedenklichem Dateinamen nach `cacheDir/share`; frühere Kopien werden entfernt. */
    private fun prepareShareFile(r: NoteRepository, note: NoteSummary): File? {
        val source = r.noteFile(note.id) ?: return null
        val dir = File(cacheDir, "share")
        if (!dir.isDirectory && !dir.mkdirs()) return null
        dir.listFiles()?.forEach { it.delete() }
        val base = note.title.map { if (it.isLetterOrDigit() || it == ' ' || it == '-' || it == '_') it else '_' }
            .joinToString("").trim().take(50).ifEmpty { "note" }
        val target = File(dir, "$base.pnote")
        return try {
            Files.copy(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            target
        } catch (e: java.io.IOException) {
            null
        }
    }

    private fun folderError(error: PnoteError): Int = when (error) {
        PnoteError.AlreadyExists -> R.string.error_folder_exists
        PnoteError.InvalidFolder -> R.string.error_folder_name_invalid
        PnoteError.NotEmpty -> R.string.error_folder_not_empty
        else -> R.string.error_action_failed
    }

    // ---------------------------------------------------------------- Dialoge

    private fun nameDialog(
        title: String,
        initial: String,
        maxLen: Int,
        errorRes: Int,
        normalize: (String) -> String,
        isValid: (String) -> Boolean,
        onSubmit: (String) -> Unit,
    ) {
        val edit = EditText(this).apply {
            setText(initial)
            setSelection(text.length)
            hint = getString(R.string.hint_name)
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine()
            filters = arrayOf(InputFilter.LengthFilter(maxLen))
            background = getDrawable(R.drawable.bg_input)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTextColor(getColor(R.color.black))
            setPadding(dp(12), 0, dp(12), 0)
        }
        val error = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(getColor(R.color.black))
            visibility = View.GONE
            setPadding(0, dp(8), 0, 0)
        }
        val dialog = openDialog(title, getString(R.string.dialog_ok), {
            val value = normalize(edit.text.toString())
            if (isValid(value)) {
                onSubmit(value)
                true
            } else {
                error.setText(errorRes)
                error.visibility = View.VISIBLE
                false
            }
        }) { body ->
            body.addView(edit, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).also { it.topMargin = dp(12) })
            body.addView(error)
        }
        edit.requestFocus()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN)
    }

    /**
     * Dialog im Stil des Vorlagendialogs (weiß, schwarzer Rand, ohne Animation). Unten "Abbrechen" und, wenn [positive]
     * gesetzt ist, die Bestätigung; [onPositive] liefert true, wenn der Dialog schließen soll.
     */
    private fun openDialog(title: String, positive: String?, onPositive: () -> Boolean = { true }, fill: (LinearLayout) -> Unit): Dialog {
        val dialog = Dialog(this, R.style.Theme_PaperNotes_TemplateDialog)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(16))
        }
        body.addView(
            TextView(this).apply {
                text = title
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                setTextColor(getColor(R.color.black))
                setTypeface(typeface, Typeface.BOLD)
            },
        )
        fill(body)
        root.addView(body)
        root.addView(lineView(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(dialogButton(getString(R.string.cancel)) { dialog.dismiss() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        if (positive != null) {
            buttons.addView(lineView(), LinearLayout.LayoutParams(dp(1), ViewGroup.LayoutParams.MATCH_PARENT))
            buttons.addView(
                dialogButton(positive) { if (onPositive()) dialog.dismiss() },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f),
            )
        }
        root.addView(buttons, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)))
        dialog.setContentView(root)
        dialog.window?.let { w ->
            w.setLayout(minOf(resources.displayMetrics.widthPixels - dp(48), dp(520)), ViewGroup.LayoutParams.WRAP_CONTENT)
            w.setWindowAnimations(0)
        }
        dialog.show()
        return dialog
    }

    private fun dialogButton(label: String, onClick: () -> Unit) =
        TextView(this, null, 0, R.style.Widget_PaperNotes_DialogButton).apply {
            text = label
            setOnClickListener { onClick() }
        }

    private fun lineView() = View(this).apply { setBackgroundColor(getColor(R.color.black)) }

    private fun messageText(message: String) = TextView(this).apply {
        text = message
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        setTextColor(getColor(R.color.black))
        setPadding(0, dp(12), 0, 0)
    }

    private fun toast(res: Int, long: Boolean = false) {
        Toast.makeText(this, res, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
    }

    private fun dp(v: Int) = Math.round(v * resources.displayMetrics.density)

    private companion object {
        const val REQUEST_STORAGE = 1
        const val PREFS = "home"
        const val KEY_LIST_MODE = "list_mode"
        const val KEY_SORT = "sort_order"
        const val STATE_FOLDER = "folder"
        const val STATE_PAGE = "page"
        const val STATE_SEARCH = "search"
        const val STATE_QUERY = "query"
    }
}

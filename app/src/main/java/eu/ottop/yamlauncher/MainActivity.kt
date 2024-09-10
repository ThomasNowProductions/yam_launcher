package eu.ottop.yamlauncher

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.database.Cursor
import android.graphics.BlendMode
import android.graphics.BlendModeColorFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.text.Editable
import android.text.TextWatcher
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView
import android.widget.Toast
import android.widget.ViewSwitcher
import androidx.activity.OnBackPressedCallback
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.res.ResourcesCompat
import androidx.core.database.getStringOrNull
import androidx.core.view.ViewCompat
import androidx.core.view.marginLeft
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.textfield.TextInputEditText
import eu.ottop.yamlauncher.databinding.ActivityMainBinding
import eu.ottop.yamlauncher.settings.SettingsActivity
import eu.ottop.yamlauncher.settings.SharedPreferenceManager
import eu.ottop.yamlauncher.tasks.BatteryReceiver
import eu.ottop.yamlauncher.tasks.ScreenLockService
import eu.ottop.yamlauncher.utils.Animations
import eu.ottop.yamlauncher.utils.AppMenuEdgeFactory
import eu.ottop.yamlauncher.utils.AppMenuLinearLayoutManager
import eu.ottop.yamlauncher.utils.AppUtils
import eu.ottop.yamlauncher.utils.GestureUtils
import eu.ottop.yamlauncher.utils.PermissionUtils
import eu.ottop.yamlauncher.utils.StringUtils
import eu.ottop.yamlauncher.utils.UIUtils
import eu.ottop.yamlauncher.utils.WeatherSystem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.lang.reflect.Method
import kotlin.math.abs


class MainActivity : AppCompatActivity(), SharedPreferences.OnSharedPreferenceChangeListener, AppMenuAdapter.OnItemClickListener, AppMenuAdapter.OnShortcutListener, AppMenuAdapter.OnItemLongClickListener, ContactsAdapter.OnContactClickListener {

    private lateinit var weatherSystem: WeatherSystem
    private lateinit var appUtils: AppUtils
    private val stringUtils = StringUtils()
    private val permissionUtils = PermissionUtils()
    private lateinit var uiUtils: UIUtils
    private lateinit var gestureUtils: GestureUtils

    private val appMenuLinearLayoutManager = AppMenuLinearLayoutManager(this@MainActivity)
    private val contactMenuLinearLayoutManager = AppMenuLinearLayoutManager(this@MainActivity)
    private val appMenuEdgeFactory = AppMenuEdgeFactory(this@MainActivity)

    private lateinit var sharedPreferenceManager: SharedPreferenceManager

    private lateinit var animations: Animations

    private lateinit var clock: TextClock
    private var clockMargin = 0
    private lateinit var dateText: TextClock
    private var dateElements = mutableListOf<String>()

    private lateinit var menuView: ViewSwitcher
    private lateinit var appRecycler: RecyclerView
    private lateinit var contactRecycler: RecyclerView
    private lateinit var searchSwitcher: ImageView
    private lateinit var searchView: TextInputEditText
    private var appAdapter: AppMenuAdapter? = null
    private var contactAdapter: ContactsAdapter? = null
    private var batteryReceiver: BatteryReceiver? = null

    private lateinit var binding: ActivityMainBinding
    private lateinit var launcherApps: LauncherApps
    private lateinit var installedApps: List<Triple<LauncherActivityInfo, UserHandle, Int>>

    private lateinit var preferences: SharedPreferences

    private var isBatteryReceiverRegistered = false
    private var isJobActive = true

    private val swipeThreshold = 100
    private val swipeVelocityThreshold = 100

    private lateinit var clockApp: Pair<LauncherActivityInfo?, Int?>
    private lateinit var dateApp: Pair<LauncherActivityInfo?, Int?>

    private lateinit var leftSwipeActivity: Pair<LauncherActivityInfo?, Int?>
    private lateinit var rightSwipeActivity: Pair<LauncherActivityInfo?, Int?>

    private lateinit var gestureDetector: GestureDetector
    private lateinit var shortcutGestureDetector: GestureDetector

    var returnAllowed = true

    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(null)

        setMainVariables()

        setShortcuts()

        setPreferences()

        setHomeListeners()

        // Task to update the app menu every 5 seconds
        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    refreshAppMenu()
                    delay(5000)
                }
            }
        }

        // Task to update the weather every 10 minutes
        lifecycleScope.launch(Dispatchers.IO) {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    updateWeather()
                    delay(600000)
                }
            }
        }
        setupApps()
    }

    private fun setMainVariables() {
        launcherApps = getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps

        weatherSystem = WeatherSystem(this@MainActivity)
        appUtils = AppUtils(this@MainActivity, launcherApps)
        uiUtils = UIUtils(this@MainActivity)
        gestureUtils = GestureUtils(this@MainActivity)
        sharedPreferenceManager = SharedPreferenceManager(this@MainActivity)
        animations = Animations(this@MainActivity)

        gestureDetector = GestureDetector(this, GestureListener())
        shortcutGestureDetector = GestureDetector(this, TextGestureListener())

        clock = binding.textClock

        clockMargin = clock.marginLeft

        dateText = binding.textDate

        dateElements = mutableListOf(dateText.format12Hour.toString(), dateText.format24Hour.toString(), "", "")

        searchView = binding.searchView

        menuView = binding.menuView

        searchSwitcher = binding.searchSwitcher

        preferences = PreferenceManager.getDefaultSharedPreferences(this)
    }

    private fun setShortcuts() {
        val shortcuts = arrayOf(R.id.app1, R.id.app2, R.id.app3, R.id.app4, R.id.app5, R.id.app6, R.id.app7, R.id.app8, R.id.app9, R.id.app10, R.id.app11, R.id.app12, R.id.app13, R.id.app14, R.id.app15)

        for (i in shortcuts.indices) {

            val textView = findViewById<TextView>(shortcuts[i])
            val shortcutNo = sharedPreferenceManager.getShortcutNumber()

            // Only show the chosen number of shortcuts (default 4). Hide the rest.
            if (i >= shortcutNo!!) {
                textView.visibility = View.GONE
            }

            else {
                textView.visibility = View.VISIBLE

                val savedView = sharedPreferenceManager.getShortcut(textView)

                // Set the non-work profile drawable by default
                textView.setCompoundDrawablesWithIntrinsicBounds(ResourcesCompat.getDrawable(resources, R.drawable.ic_empty, null),null,null,null)

                shortcutListeners(textView)

                if (savedView?.get(1) != "e") {
                    setShortcutSetup(textView, savedView)
                }
                else {
                    unsetShortcutSetup(textView)
                }
            }
        }
        uiUtils.setShortcutsAlignment(binding.homeView)
        uiUtils.setShortcutsVAlignment(binding.topSpace, binding.bottomSpace)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun shortcutListeners(textView: TextView) {
        // Don't go to settings on long click, but keep other gestures functional
        textView.setOnTouchListener {_, event ->
            shortcutGestureDetector.onTouchEvent(event)
            super.onTouchEvent(event)
        }

        ViewCompat.addAccessibilityAction(textView, "Set Shortcut App") { _, _ ->
            uiUtils.setMenuTitleAlignment(binding.menuTitle)
            uiUtils.setMenuTitleSize(binding.menuTitle)
            binding.menuTitle.visibility = View.VISIBLE

            appAdapter?.shortcutTextView = textView
            toAppMenu()
            true
        }

        ViewCompat.addAccessibilityAction(textView, "Launcher Settings") { _, _ ->
            startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
            true
        }

        ViewCompat.addAccessibilityAction(textView, "Open App Menu") { _, _ ->
            openAppMenu()
            true
        }

        textView.setOnLongClickListener {
            uiUtils.setMenuTitleAlignment(binding.menuTitle)
            uiUtils.setMenuTitleSize(binding.menuTitle)
            binding.menuTitle.visibility = View.VISIBLE

            appAdapter?.shortcutTextView = textView
            toAppMenu()
            searchSwitcher.visibility = View.GONE

            return@setOnLongClickListener true
        }
    }

    private fun toAppMenu() {
        try {
            // The menu opens from the top
            appRecycler.scrollToPosition(0)
            if (searchSwitcher.visibility == View.VISIBLE) {
                contactRecycler.scrollToPosition(0)
                menuView.displayedChild = 0
                setAppViewDetails()
            }
        }
        catch (_: UninitializedPropertyAccessException) {}
        animations.showApps(binding.homeView, binding.appView)
        animations.backgroundIn(this@MainActivity)
        if (sharedPreferenceManager.isAutoKeyboardEnabled()) {
            val imm =
                getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            searchView.requestFocus()
            imm.showSoftInput(searchView, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun unsetShortcutSetup(textView: TextView) {
        textView.text = getString(R.string.shortcut_default)
        unsetShortcutListeners(textView)
    }

    private fun unsetShortcutListeners(textView: TextView) {
        textView.setOnClickListener {
            Toast.makeText(this, "Long click to select an app", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setShortcutSetup(textView: TextView, savedView: List<String>?) {
        // Set the work profile drawable for work profile apps
        if (savedView?.get(1) != "0") {
            textView.setCompoundDrawablesWithIntrinsicBounds(ResourcesCompat.getDrawable(resources, R.drawable.ic_work_app, null),null,null,null)
        }

        textView.text = savedView?.get(2)
        setShortcutListeners(textView, savedView)
    }

    private fun setShortcutListeners(textView: TextView, savedView: List<String>?) {
        textView.setOnClickListener {
            if (savedView != null) {
                appUtils.launchApp(savedView[0], launcherApps.profiles[savedView[1].toInt()])
            }
        }
    }

    private fun setPreferences() {
        uiUtils.setBackground(window)

        uiUtils.setTextFont(binding.homeView)
        uiUtils.setFont(searchView)
        uiUtils.setFont(binding.menuTitle)

        uiUtils.setTextColors(binding.homeView)

        uiUtils.setMenuItemColors(binding.menuTitle, "A9")

        uiUtils.setClockVisibility(clock)
        uiUtils.setDateVisibility(dateText)
        uiUtils.setSearchVisibility(searchView, binding.searchLayout, binding.searchReplacement)

        uiUtils.setClockAlignment(clock, dateText)
        uiUtils.setSearchAlignment(searchView)

        uiUtils.setClockSize(clock)
        uiUtils.setDateSize(dateText)
        uiUtils.setShortcutsSize(binding.homeView)
        uiUtils.setSearchSize(searchView)

        uiUtils.setShortcutsSpacing(binding.homeView)

        // This didn't work and somehow delaying it by 0 makes it work
        handler.postDelayed({
            uiUtils.setStatusBar(window)
            uiUtils.setMenuItemColors(searchView)
        }, 100)

        clockApp = gestureUtils.getSwipeInfo(launcherApps, "clock")
        dateApp = gestureUtils.getSwipeInfo(launcherApps, "date")

        leftSwipeActivity = gestureUtils.getSwipeInfo(launcherApps, "left")
        rightSwipeActivity = gestureUtils.getSwipeInfo(launcherApps, "right")
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setHomeListeners() {
        registerBatteryReceiver()

        if (!sharedPreferenceManager.isBatteryEnabled()) {
            unregisterBatteryReceiver()
        }

        preferences.registerOnSharedPreferenceChangeListener(this)

        binding.homeView.setOnTouchListener { _, event ->
            super.onTouchEvent(event)
            gestureDetector.onTouchEvent(event)
            true
        }

        clock.setOnClickListener {_ ->
            if (sharedPreferenceManager.isClockGestureEnabled()) {
                if (sharedPreferenceManager.isGestureEnabled("clock") && clockApp.first != null && clockApp.second != null) {
                    launcherApps.startMainActivity(clockApp.first!!.componentName,  launcherApps.profiles[clockApp.second!!], null, null)
                } else {
                    val intent = Intent(AlarmClock.ACTION_SHOW_ALARMS)
                    if (intent.resolveActivity(packageManager) != null) {
                        startActivity(intent)
                    }
                }
            }
        }

        dateText.setOnClickListener { _ ->
            if (sharedPreferenceManager.isDateGestureEnabled()) {

                if (sharedPreferenceManager.isGestureEnabled("date") && dateApp.first != null && dateApp.second != null) {
                    launcherApps.startMainActivity(dateApp.first!!.componentName,  launcherApps.profiles[dateApp.second!!], null, null)
                } else {
                    startActivity(
                        Intent(
                            Intent.makeMainSelectorActivity(
                                Intent.ACTION_MAIN,
                                Intent.CATEGORY_APP_CALENDAR
                            )
                        )
                    )
                }
            }
        }

        clock.setOnLongClickListener {_ ->
            startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
            true
        }

        dateText.setOnLongClickListener {_ ->
            startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
            true
        }

        ViewCompat.addAccessibilityAction(binding.homeView, "Launcher Settings") { _, _ ->
            startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
            true
        }

        ViewCompat.addAccessibilityAction(binding.homeView, "Open App Menu") { _, _ ->
            openAppMenu()
            true
        }

        // Return to home on back
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                backToHome()
            }
        })
    }

    private fun registerBatteryReceiver() {
        if (!isBatteryReceiverRegistered) {
            batteryReceiver = BatteryReceiver.register(this, this@MainActivity)
            isBatteryReceiverRegistered = true
        }
    }

    private fun unregisterBatteryReceiver() {
        if (isBatteryReceiverRegistered) {
            unregisterReceiver(batteryReceiver)
            isBatteryReceiverRegistered = false
        }
    }

    private fun openAppMenu() {
        appAdapter?.shortcutTextView = null
        binding.menuTitle.visibility = View.GONE
        uiUtils.setContactsVisibility(searchSwitcher, binding.searchLayout, binding.searchReplacement)
        toAppMenu()
    }

    // Only reload items that have had preferences changed
    override fun onSharedPreferenceChanged(preferences: SharedPreferences?, key: String?) {
        if (preferences != null) {
            when (key) {
                "bgColor" -> {
                    uiUtils.setBackground(window)
                }

                "textColor" -> {
                    uiUtils.setTextColors(binding.homeView)
                    uiUtils.setMenuItemColors(searchView)
                    uiUtils.setMenuItemColors(binding.menuTitle, "A9")
                    uiUtils.setImageColor(searchSwitcher)
                }

                "textFont" -> {
                    uiUtils.setTextFont(binding.homeView)
                    uiUtils.setFont(searchView)
                    uiUtils.setFont(binding.menuTitle)
                }

                "textStyle" -> {
                    uiUtils.setTextFont(binding.homeView)
                    uiUtils.setFont(searchView)
                    uiUtils.setFont(binding.menuTitle)
                }

                "clockEnabled" -> {
                    uiUtils.setClockVisibility(clock)
                }

                "dateEnabled" -> {
                    uiUtils.setDateVisibility(dateText)
                }

                "searchEnabled" -> {
                    uiUtils.setSearchVisibility(searchView, binding.searchLayout, binding.searchReplacement)
                }

                "contactsEnabled" -> {
                    try{
                        contactRecycler
                    } catch(_: UninitializedPropertyAccessException) {
                        setupContactRecycler()
                    }
                }

                "clockAlignment" -> {
                    uiUtils.setClockAlignment(clock, dateText)
                }

                "shortcutAlignment" -> {
                    uiUtils.setShortcutsAlignment(binding.homeView)
                }

                "shortcutVAlignment" -> {
                    uiUtils.setShortcutsVAlignment(binding.topSpace, binding.bottomSpace)
                }

                "searchAlignment" -> {
                    uiUtils.setSearchAlignment(searchView)
                }

                "clockSize" -> {
                    uiUtils.setClockSize(clock)
                }

                "dateSize" -> {
                    uiUtils.setDateSize(dateText)
                }

                "shortcutSize" -> {
                    uiUtils.setShortcutsSize(binding.homeView)
                }

                "searchSize" -> {
                    uiUtils.setSearchSize(searchView)
                }

                "shortcutWeight" -> {
                    uiUtils.setShortcutsSpacing(binding.homeView)
                }

                "barVisibility" -> {
                    uiUtils.setStatusBar(window)
                }

                "clockSwipe" -> {
                    clockApp = gestureUtils.getSwipeInfo(launcherApps, "clock")
                }

                "dateSwipe" -> {
                    dateApp = gestureUtils.getSwipeInfo(launcherApps, "date")
                }

                "clockSwipeApp" -> {
                    clockApp = gestureUtils.getSwipeInfo(launcherApps, "clock")
                }

                "dateSwipeApp" -> {
                    dateApp = gestureUtils.getSwipeInfo(launcherApps, "date")
                }

                "leftSwipe" -> {
                    leftSwipeActivity = gestureUtils.getSwipeInfo(launcherApps, "left")
                }

                "rightSwipe" -> {
                    rightSwipeActivity = gestureUtils.getSwipeInfo(launcherApps, "right")
                }

                "leftSwipeApp" -> {
                    leftSwipeActivity = gestureUtils.getSwipeInfo(launcherApps, "left")
                }

                "rightSwipeApp" -> {
                    rightSwipeActivity = gestureUtils.getSwipeInfo(launcherApps, "right")
                }

                "batteryEnabled" -> {
                    if (sharedPreferenceManager.isBatteryEnabled()) {
                        registerBatteryReceiver()
                    } else {
                        unregisterBatteryReceiver()
                        modifyDate("", 3)
                    }
                }

                "shortcutNo" -> {
                    setShortcuts()
                }

                "isRestored" -> {
                    preferences.edit().remove("isRestored").apply()
                    setPreferences()
                    setShortcuts()
                }
            }
        }
    }

    fun modifyDate(value: String, index: Int) {
        /*Indexes:
        0 = 12h time
        1 = 24h time
        2 = Weather
        3 = Battery level*/
        dateElements[index] = value
        dateText.format12Hour = "${dateElements[0]}${stringUtils.addStartTextIfNotEmpty(dateElements[2], " | ")}${stringUtils.addStartTextIfNotEmpty(dateElements[3], " | ")}"
        dateText.format24Hour = "${dateElements[1]}${stringUtils.addStartTextIfNotEmpty(dateElements[2], " | ")}${stringUtils.addStartTextIfNotEmpty(dateElements[3], " | ")}"
    }

    fun backToHome(animSpeed: Long = sharedPreferenceManager.getAnimationSpeed()) {
        closeKeyboard()
        animations.showHome(binding.homeView, binding.appView, animSpeed)
        animations.backgroundOut(this@MainActivity, animSpeed)

        // Delay app menu changes so that the user doesn't see them

        handler.postDelayed({
            try {
                searchView.setText(R.string.empty)
                appMenuLinearLayoutManager.setScrollEnabled(true)
            }
            catch (_: UninitializedPropertyAccessException) {

            }
        }, animSpeed)

        handler.postDelayed({
            lifecycleScope.launch {
                refreshAppMenu()
            }}, animSpeed + 50)

    }

    private fun closeKeyboard() {
        val imm =
            getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.root.windowToken, 0)
    }

    suspend fun refreshAppMenu() {
        try {

            // Don't reset app menu while under a search
            if (isJobActive) {
                val updatedApps = appUtils.getInstalledApps()
                if (!listsEqual(installedApps, updatedApps)) {

                    updateMenu(updatedApps)

                    installedApps = updatedApps
                }
            }
        }
        catch (_: UninitializedPropertyAccessException) {
        }
    }

    private fun listsEqual(list1: List<Triple<LauncherActivityInfo, UserHandle, Int>>, list2: List<Triple<LauncherActivityInfo, UserHandle, Int>>): Boolean {
        if (list1.size != list2.size) return false

        for (i in list1.indices) {
            if (list1[i].first.componentName != list2[i].first.componentName || list1[i].second != list2[i].second) {
                return false
            }
        }

        return true
    }

    private suspend fun updateMenu(updatedApps: List<Triple<LauncherActivityInfo, UserHandle, Int>>) {
        withContext(Dispatchers.Main) {
            appAdapter?.updateApps(updatedApps)
        }
    }

    private suspend fun updateWeather() {
        withContext(Dispatchers.IO) {
            if (sharedPreferenceManager.isWeatherEnabled()) {
                    updateWeatherText()
            }
            else {
                withContext(Dispatchers.Main) {
                    modifyDate("", 2)
                }
            }
        }
    }

    suspend fun updateWeatherText() {
        val temp = weatherSystem.getTemp()
        withContext(Dispatchers.Main) {
            modifyDate(temp, 2)
        }
    }

    private fun setupApps() {
        lifecycleScope.launch(Dispatchers.Default) {
            installedApps = appUtils.getInstalledApps()
            val newApps = installedApps.toMutableList()

            setupAppRecycler(newApps)

            setupSearch()
            if (sharedPreferenceManager.areContactsEnabled()) {
                setupContactRecycler()
            }
        }

    }

    private suspend fun setupAppRecycler(newApps: MutableList<Triple<LauncherActivityInfo, UserHandle, Int>>) {
        appAdapter = AppMenuAdapter(this@MainActivity, binding, newApps, this@MainActivity, this@MainActivity, this@MainActivity, launcherApps)
        appMenuLinearLayoutManager.stackFromEnd = true
        appRecycler = binding.appRecycler
        withContext(Dispatchers.Main) {
            appRecycler.layoutManager = appMenuLinearLayoutManager
            appRecycler.edgeEffectFactory = appMenuEdgeFactory
            appRecycler.adapter = appAdapter

        }

        setupRecyclerListener(appRecycler, appMenuLinearLayoutManager)
    }

    // Inform the layout manager of scroll states to calculate whether the menu is on the top
    private fun setupRecyclerListener(recycler:RecyclerView, layoutManager: AppMenuLinearLayoutManager) {
        recycler.addOnScrollListener(object: RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                super.onScrollStateChanged(recyclerView, newState)
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                    layoutManager.setScrollInfo()
                }
            }
        })
    }

    private fun setupContactRecycler() {
        uiUtils.setImageColor(searchSwitcher)

        contactAdapter = ContactsAdapter(this, mutableListOf(), this)
        contactMenuLinearLayoutManager.stackFromEnd = true
        contactRecycler = binding.contactRecycler
        contactRecycler.layoutManager = contactMenuLinearLayoutManager
        contactRecycler.edgeEffectFactory = appMenuEdgeFactory
        contactRecycler.adapter = contactAdapter
        setupRecyclerListener(contactRecycler, contactMenuLinearLayoutManager)

        searchSwitcher.setOnClickListener {
            switchMenus()
        }
    }

    fun switchMenus() {
        menuView.showNext()
        when (menuView.displayedChild) {
            0 -> {
                setAppViewDetails()
            }
            1 -> {
                setContactViewDetails()
            }
        }
    }

    private fun setAppViewDetails() {
        searchSwitcher.setImageDrawable(
            ResourcesCompat.getDrawable(
                resources,
                R.drawable.contacts_24px,
                null
            )
        )
        searchSwitcher.contentDescription = getString(R.string.switch_to_contacts)
    }

    private fun setContactViewDetails() {
        lifecycleScope.launch(Dispatchers.Default) {
            filterItems(searchView.text.toString())
        }
        searchSwitcher.setImageDrawable(ResourcesCompat.getDrawable(resources, R.drawable.apps_24px, null))
        searchSwitcher.contentDescription = getString(R.string.switch_to_apps)
    }

    private fun getContacts(filterString: String): MutableList<Pair<String, Int>> {
        val contacts = mutableListOf<Pair<String, Int>>()

        val contentResolver: ContentResolver = contentResolver

        val projection = arrayOf(
            ContactsContract.Contacts._ID,
            ContactsContract.Contacts.DISPLAY_NAME
        )

        val selection = "${ContactsContract.Contacts.DISPLAY_NAME} LIKE ?"
        val selectionArgs = arrayOf("%$filterString%")

        val cursor: Cursor? = contentResolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            "${ContactsContract.Contacts.DISPLAY_NAME} ASC"
        )

        cursor?.use {
            val nameIndex = it.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME)
            val idIndex = it.getColumnIndex(ContactsContract.Contacts._ID)
            while (it.moveToNext()) {
                val name = it.getStringOrNull(nameIndex)
                val id = it.getStringOrNull(idIndex)?.toInt()
                if (name != null && id != null) {
                    contacts.add(Pair(name, id))
                }
            }
        }
        return contacts
    }



    private suspend fun updateContacts(filterString: String) {
        val contacts = getContacts(filterString)
        withContext(Dispatchers.Main) {
            contactAdapter?.updateContacts(contacts)
        }
    }

    private suspend fun setupSearch() {
        binding.appView.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->

            if (bottom - top > oldBottom - oldTop) {
                // If keyboard is closed, remove cursor from the search bar
                searchView.clearFocus()
            }

        }

        searchView.addTextChangedListener(object :
            TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
            }

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {

            }

            override fun afterTextChanged(s: Editable?) {
                lifecycleScope.launch(Dispatchers.Default) {
                    filterItems(s.toString())
                }
            }
        })
    }

    private suspend fun filterItems(query: String?) {

        val cleanQuery = stringUtils.cleanString(query)
        val newFilteredApps = mutableListOf<Triple<LauncherActivityInfo, UserHandle, Int>>()
        val updatedApps = appUtils.getInstalledApps()

        getFilteredApps(cleanQuery, newFilteredApps, updatedApps)
        if (sharedPreferenceManager.areContactsEnabled() && cleanQuery != null) {
            updateContacts(cleanQuery)
        }
    }

    private suspend fun getFilteredApps(cleanQuery: String?, newFilteredApps: MutableList<Triple<LauncherActivityInfo, UserHandle, Int>>, updatedApps: List<Triple<LauncherActivityInfo, UserHandle, Int>>) {
        if (cleanQuery.isNullOrEmpty()) {
            isJobActive = true
            updateMenu(updatedApps)
        } else {
            isJobActive = false
            updatedApps.forEach {
                val cleanItemText = stringUtils.cleanString(sharedPreferenceManager.getAppName(
                    it.first.applicationInfo.packageName,
                    it.third,
                    packageManager.getApplicationLabel(it.first.applicationInfo)
                ).toString())
                if (cleanItemText != null) {
                    if (cleanItemText.contains(cleanQuery, ignoreCase = true)) {
                        newFilteredApps.add(it)
                    }
                }
            }
            applySearchFilter(newFilteredApps)
        }
    }

    private suspend fun applySearchFilter(newFilteredApps: MutableList<Triple<LauncherActivityInfo, UserHandle, Int>>) {
        if (sharedPreferenceManager.isAutoLaunchEnabled() && menuView.displayedChild == 0 && appAdapter?.shortcutTextView == null && newFilteredApps.size == 1) {
            appUtils.launchApp(newFilteredApps[0].first.applicationInfo.packageName, newFilteredApps[0].second)
        } else if (!listsEqual(installedApps, newFilteredApps)) {
            updateMenu(newFilteredApps)

            installedApps = newFilteredApps
        }
    }

    suspend fun applySearch() {
        withContext(Dispatchers.Default) {
            filterItems(searchView.text.toString())
        }
    }

    fun disableAppMenuScroll() {
        appMenuLinearLayoutManager.setScrollEnabled(false)
        appRecycler.layoutManager = appMenuLinearLayoutManager
    }

    fun enableAppMenuScroll() {
        appMenuLinearLayoutManager.setScrollEnabled(true)
        appRecycler.layoutManager = appMenuLinearLayoutManager
    }

    // On home key or swipe, return to home screen
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        backToHome()
    }

    override fun onDestroy() {
        super.onDestroy()

        unregisterBatteryReceiver()
        preferences.unregisterOnSharedPreferenceChangeListener(this)
    }

    override fun onStart() {
        super.onStart()
        // Keyboard is sometimes open when going back to the app, so close it.
        closeKeyboard()
    }

    @SuppressLint("NotifyDataSetChanged")
    override fun onResume() {
        super.onResume()
        if (!permissionUtils.hasContactsPermission(this@MainActivity, Manifest.permission.READ_CONTACTS)) {
            sharedPreferenceManager.setContactsEnabled(false)
        }
        if (returnAllowed) {
            backToHome(0)
        }
        returnAllowed = true
        appAdapter?.notifyDataSetChanged()
    }

    override fun onItemClick(appInfo: LauncherActivityInfo, userHandle: UserHandle) {
        appUtils.launchApp(appInfo.applicationInfo.packageName, userHandle)
    }

    override fun onShortcut(
        appInfo: LauncherActivityInfo,
        userHandle: UserHandle,
        textView: TextView,
        userProfile: Int,
        shortcutView: TextView
    ) {
        if (userProfile != 0) {
            shortcutView.setCompoundDrawablesWithIntrinsicBounds(ResourcesCompat.getDrawable(resources, R.drawable.ic_work_app, null),null,null,null)
            shortcutView.compoundDrawables[0]?.colorFilter =
                BlendModeColorFilter(sharedPreferenceManager.getTextColor(), BlendMode.SRC_ATOP)
            shortcutView.compoundDrawables[2]?.colorFilter =
                BlendModeColorFilter(sharedPreferenceManager.getTextColor(), BlendMode.SRC_ATOP)
        }
        else {
            shortcutView.setCompoundDrawablesWithIntrinsicBounds(ResourcesCompat.getDrawable(resources, R.drawable.ic_empty, null),null,null,null)
        }

        shortcutView.text = textView.text.toString()
        shortcutView.setOnClickListener {
            appUtils.launchApp(appInfo.applicationInfo.packageName, userHandle)
        }
        sharedPreferenceManager.setShortcut(
            shortcutView,
            appInfo.applicationInfo.packageName,
            userProfile
        )
        uiUtils.setDrawables(shortcutView, sharedPreferenceManager.getShortcutAlignment())
        backToHome()
    }


    override fun onItemLongClick(
        appInfo: LauncherActivityInfo,
        userHandle: UserHandle,
        userProfile: Int,
        textView: TextView,
        actionMenuLayout: LinearLayout,
        editView: LinearLayout,
        position: Int
    ) {
        textView.visibility = View.INVISIBLE
        animations.fadeViewIn(actionMenuLayout)
    }

    open inner class GestureListener : GestureDetector.SimpleOnGestureListener() {

        @RequiresApi(Build.VERSION_CODES.TIRAMISU)
        @SuppressLint("WrongConstant")
        override fun onFling(
            e1: MotionEvent?,
            e2: MotionEvent,
            velocityX: Float,
            velocityY: Float
        ): Boolean {
            if (e1 != null) {
                val deltaY = e2.y - e1.y
                val deltaX = e2.x - e1.x

                // Swipe up
                if (deltaY < -swipeThreshold && abs(velocityY) > swipeVelocityThreshold) {
                    openAppMenu()
                }

                // Swipe down
                else if (deltaY > swipeThreshold && abs(velocityY) > swipeVelocityThreshold) {
                    val statusBarService = getSystemService(Context.STATUS_BAR_SERVICE)
                    val statusBarManager: Class<*> = Class.forName("android.app.StatusBarManager")
                    val expandMethod: Method = statusBarManager.getMethod("expandNotificationsPanel")
                    expandMethod.invoke(statusBarService)
                }

                // Swipe left
                else if (deltaX < -swipeThreshold && abs(velocityX) > swipeVelocityThreshold && sharedPreferenceManager.isGestureEnabled("left")){
                    if (leftSwipeActivity.first != null && leftSwipeActivity.second != null) {
                        launcherApps.startMainActivity(leftSwipeActivity.first!!.componentName,  launcherApps.profiles[leftSwipeActivity.second!!], null, null)
                    } else {
                        Toast.makeText(this@MainActivity, "Cannot launch app", Toast.LENGTH_SHORT).show()
                    }
                }


                // Swipe right
                else if (deltaX > -swipeThreshold && abs(velocityX) > swipeVelocityThreshold && sharedPreferenceManager.isGestureEnabled("right")) {
                    if (rightSwipeActivity.first != null && rightSwipeActivity.second != null) {
                        launcherApps.startMainActivity(rightSwipeActivity.first!!.componentName,  launcherApps.profiles[rightSwipeActivity.second!!], null, null)
                    } else {
                        Toast.makeText(this@MainActivity, "Cannot launch app", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            super.onLongPress(e)
            startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (sharedPreferenceManager.isDoubleTapEnabled()) {
                if (gestureUtils.isAccessibilityServiceEnabled(
                        ScreenLockService::class.java
                    )
                ) {
                    val intent = Intent(this@MainActivity, ScreenLockService::class.java)
                    intent.action = "LOCK_SCREEN"
                    startService(intent)
                } else {
                    gestureUtils.promptEnableAccessibility()
                }
            }

            return super.onDoubleTap(e)

        }

    }

    inner class TextGestureListener : GestureListener() {
        override fun onLongPress(e: MotionEvent) {

        }
    }

    override fun onContactClick(contactId: Int) {
        val contactUri: Uri = Uri.withAppendedPath(
            ContactsContract.Contacts.CONTENT_URI,
            contactId.toString()
        )

        val intent = Intent(Intent.ACTION_VIEW, contactUri)
        startActivity(intent)
        returnAllowed = false
    }
}
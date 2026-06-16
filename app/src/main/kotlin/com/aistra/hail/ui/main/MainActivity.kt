package com.aistra.hail.ui.main

import android.os.Bundle
import android.view.Menu
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.core.view.MenuCompat
import androidx.core.view.WindowCompat
import androidx.core.view.isVisible
import androidx.navigation.NavController
import androidx.navigation.NavDestination
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.navigateUp
import androidx.navigation.ui.onNavDestinationSelected
import androidx.navigation.ui.setupActionBarWithNavController
import com.aistra.hail.R
import com.aistra.hail.app.HailData
import com.aistra.hail.databinding.ActivityMainBinding
import com.aistra.hail.extensions.*
import com.aistra.hail.utils.HPolicy
import com.aistra.hail.utils.HUI
import com.aistra.hail.views.T9KeyboardView
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton

class MainActivity : AppCompatActivity(), NavController.OnDestinationChangedListener {
    lateinit var fab: ExtendedFloatingActionButton
    lateinit var appbar: AppBarLayout
    lateinit var t9Keyboard: T9KeyboardView
    lateinit var homeSearchBar: View
    lateinit var homeSearchIcon: ImageView
    lateinit var homeSearchInput: EditText
    private lateinit var navController: NavController
    private lateinit var appBarConfiguration: AppBarConfiguration

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val binding = initView()
        if (!HailData.biometricLogin || BiometricManager.from(this)
                .canAuthenticate(BIOMETRIC_STRONG or DEVICE_CREDENTIAL) != BiometricManager.BIOMETRIC_SUCCESS
        ) return
        binding.root.isVisible = false
        val biometricPrompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    HUI.showToast(errString)
                    finishAndRemoveTask()
                }

                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    binding.root.isVisible = true
                }
            })
        val promptInfo = BiometricPrompt.PromptInfo.Builder().setTitle(getString(R.string.action_biometric))
            .setSubtitle(getString(R.string.msg_biometric)).setNegativeButtonText(getString(android.R.string.cancel))
            .build()
        biometricPrompt.authenticate(promptInfo)
    }

    private fun initView() = ActivityMainBinding.inflate(layoutInflater).apply {
        setContentView(root)
        setSupportActionBar(appBarMain.toolbar)
        fab = appBarMain.fab
        appbar = appBarMain.appBarLayout
        t9Keyboard = appBarMain.t9Keyboard
        homeSearchBar = appBarMain.homeSearchBar
        homeSearchIcon = appBarMain.homeSearchIcon
        homeSearchInput = appBarMain.homeSearchInput
        t9Keyboard.applyDefaultInsetter { marginRelative(isRtl, bottom = true) }

        val navHostFragment = supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        navController = navHostFragment.navController
        navController.addOnDestinationChangedListener(this@MainActivity)
        appBarConfiguration = AppBarConfiguration.Builder(R.id.nav_home).build()
        setupActionBarWithNavController(navController, appBarConfiguration)

        val isRtl = isRtl
        appBarMain.appBarLayout.applyDefaultInsetter {
            paddingRelative(isRtl, start = true, end = true, top = true)
        }
        fab.applyDefaultInsetter { marginRelative(isRtl, end = true, bottom = true) }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        super.onCreateOptionsMenu(menu)
        menu?.let {
            menuInflater.inflate(R.menu.nav_main, it)
            MenuCompat.setGroupDividerEnabled(it, true)
        }
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean = when (item.itemId) {
        R.id.nav_apps, R.id.nav_settings, R.id.nav_about -> item.onNavDestinationSelected(navController)
        else -> super.onOptionsItemSelected(item)
    }

    override fun onSupportNavigateUp(): Boolean = navController.navigateUp(appBarConfiguration)

    fun ownerRemoveDialog() {
        MaterialAlertDialogBuilder(this).setTitle(R.string.title_remove_owner).setMessage(R.string.msg_remove_owner)
            .setPositiveButton(R.string.action_continue) { _, _ ->
                HPolicy.setOrganizationName()
                HPolicy.removeDeviceOwner()
            }.setNegativeButton(android.R.string.cancel, null).show()
    }

    /* override fun onStop() {
        super.onStop()
        if (HailData.biometricLogin) finishAndRemoveTask()
    } */

    override fun onDestinationChanged(
        controller: NavController, destination: NavDestination, arguments: Bundle?
    ) {
        fab.tag = destination.id == R.id.nav_home
        homeSearchBar.isVisible = destination.id == R.id.nav_home
        if (fab.tag == true) fab.show() else fab.hide()
        invalidateOptionsMenu()
    }
}

package dev.cerbos.epdpdemo

import android.annotation.SuppressLint
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import android.view.Menu
import android.view.MenuItem
import dev.cerbos.epdp.CerbosEmbeddedPDPWebView
import dev.cerbos.epdpdemo.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var cerbosEmbeddedPDP: CerbosEmbeddedPDPWebView


    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)

        cerbosEmbeddedPDP = findViewById(R.id.cerbosPDP)

        cerbosEmbeddedPDP.onDecision = {
            Log.d("App", "Decision: $it")
        }

        cerbosEmbeddedPDP.setOnReadyListener {
            Log.d("App", "PDP is ready")
            cerbosEmbeddedPDP.checkResources(
                dev.cerbos.epdp.CerbosEmbeddedPDPWebView.CheckResourcesRequest(
                    requestId = null,

                    principal = dev.cerbos.epdp.CerbosEmbeddedPDPWebView.Principal(
                        id = "123",
                        policyVersion = "default",
                        roles = listOf(),

                    ),
                    resources = listOf(
                        dev.cerbos.epdp.CerbosEmbeddedPDPWebView.Resource(
                            resource = dev.cerbos.epdp.CerbosEmbeddedPDPWebView.ResourceObject(
                                id = "456",
                                kind = "resource",
                                policyVersion = "default",
                            ),
                            actions = listOf(
                                "read"
                            )
                        )
                    )
                )
            )
        }

        cerbosEmbeddedPDP.loadEmbeddedPDP("https://lite.cerbos.cloud/bundle?workspace=7SRBU5GTJZKS&label=f481a2c9c90ee3ae4deae7b7f656d65d1cd608828f5853d21e9ca383d479223a")

    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        // Inflate the menu; this adds items to the action bar if it is present.
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        return when (item.itemId) {
            R.id.action_settings -> true
            else -> super.onOptionsItemSelected(item)
        }
    }

}
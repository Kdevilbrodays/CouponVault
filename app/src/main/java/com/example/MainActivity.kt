package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.couponvault.CouponVaultApplication
import com.example.couponvault.ui.CouponViewModel
import com.example.couponvault.ui.screens.CaptureScreen
import com.example.couponvault.ui.screens.CouponDetailScreen
import com.example.couponvault.ui.screens.CouponListScreen
import com.example.couponvault.ui.screens.ReviewQueueScreen
import com.example.couponvault.ui.theme.CouponVaultTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val app = application as CouponVaultApplication
        val repository = app.repository
        val queueManager = app.queueManager
        val preferences = app.overlayPreferences

        setContent {
            CouponVaultTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val viewModel: CouponViewModel = viewModel(
                        factory = CouponViewModel.provideFactory(repository, queueManager, preferences)
                    )

                    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
                    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
                        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                                viewModel.resyncOverlayState(this@MainActivity)
                            }
                        }
                        lifecycleOwner.lifecycle.addObserver(observer)
                        onDispose {
                            lifecycleOwner.lifecycle.removeObserver(observer)
                        }
                    }

                    CouponVaultAppNav(viewModel = viewModel)
                }
            }
        }
    }
}

@Composable
fun CouponVaultAppNav(viewModel: CouponViewModel) {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = "coupons"
    ) {
        composable("coupons") {
            CouponListScreen(
                viewModel = viewModel,
                onNavigateToCapture = {
                    viewModel.resetSessionCount()
                    navController.navigate("capture")
                },
                onNavigateToDetail = { couponId ->
                    navController.navigate("detail/$couponId")
                },
                onNavigateToReviewQueue = {
                    navController.navigate("review_queue")
                }
            )
        }

        composable("capture") {
            CaptureScreen(
                viewModel = viewModel,
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }

        composable("review_queue") {
            ReviewQueueScreen(
                viewModel = viewModel,
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }

        composable(
            route = "detail/{couponId}",
            arguments = listOf(
                navArgument("couponId") { type = NavType.LongType }
            )
        ) { backStackEntry ->
            val couponId = backStackEntry.arguments?.getLong("couponId") ?: 0L
            CouponDetailScreen(
                couponId = couponId,
                viewModel = viewModel,
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }
    }
}

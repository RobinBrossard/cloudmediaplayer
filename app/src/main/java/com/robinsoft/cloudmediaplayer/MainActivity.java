package com.robinsoft.cloudmediaplayer;

import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.WindowInsetsController;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;
import androidx.media3.common.util.UnstableApi;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.robinsoft.cloudmediaplayer.fragment.HomeFragment;
import com.robinsoft.cloudmediaplayer.fragment.TestFragment;

@UnstableApi
public class MainActivity extends AppCompatActivity {
    private static final String TAG_HOME = "home";
    private static final String TAG_TEST = "test_fr";
    private static final String TAG_MAIN_ACTIVITY_INSETS = "MainActivityInsets";

    private HomeFragment homeFragment;
    private TestFragment testFragment;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 1. 确保边到边显示
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_main);

        // 2. 隐藏状态栏和导航栏
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false); // 再次确认 decorFitsSystemWindows 为 false
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsetsCompat.Type.statusBars() | WindowInsetsCompat.Type.navigationBars());
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            // For older versions (Android 10 and below)
            View decorView = getWindow().getDecorView();
            int uiOptions = View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
            decorView.setSystemUiVisibility(uiOptions);
        }
        
        // 2. 为关键视图应用 Insets
        View fragmentContainer = findViewById(R.id.fragment_container);
        View bottomNav = findViewById(R.id.bottom_nav);

        // fragment_container: 它需要知道状态栏的高度，以便在状态栏可见时，其顶部内容不被遮挡。
        // HomeFragment 会在其内部全屏时，通过自己的逻辑来利用这部分空间。
        if (fragmentContainer != null) {
            ViewCompat.setOnApplyWindowInsetsListener(fragmentContainer, (v, windowInsets) -> {
                Insets systemBars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
                // 仅当状态栏可见时，应用顶部 padding。左右 padding 始终应用。
                boolean isStatusBarVisible = windowInsets.isVisible(WindowInsetsCompat.Type.statusBars());
                int paddingTop = isStatusBarVisible ? systemBars.top : 0;

                v.setPadding(systemBars.left, paddingTop, systemBars.right, 0); // 底部由约束决定，不在这里设padding
                Log.d(TAG_MAIN_ACTIVITY_INSETS, "FragmentContainer padding set: L" + systemBars.left +
                        " T" + paddingTop + " R" + systemBars.right + " B0. StatusBarVisible: " + isStatusBarVisible);
                // 返回原始 insets，HomeFragment 可能需要原始的全局信息
                return windowInsets;
            });
        }

        if (bottomNav != null) {
            ViewCompat.setOnApplyWindowInsetsListener(bottomNav, (v, windowInsets) -> {
                // BottomNav 主要关心系统导航栏和IME的底部空间
                Insets navAndIme = windowInsets.getInsets(
                        WindowInsetsCompat.Type.navigationBars() | WindowInsetsCompat.Type.ime()
                );
                v.setPadding(
                        v.getPaddingLeft(), // 保留XML中可能已有的左右上padding
                        v.getPaddingTop(),
                        v.getPaddingRight(),
                        navAndIme.bottom // 应用底部padding
                );
                Log.d(TAG_MAIN_ACTIVITY_INSETS, "BottomNav paddingBottom updated to: " + navAndIme.bottom);
                // BottomNav 消费它处理的 insets
                return WindowInsetsCompat.CONSUMED;
            });
        }

        // ... (Fragment 管理逻辑保持您之前的版本) ...
        FragmentManager fm = getSupportFragmentManager();
        FragmentTransaction ft = fm.beginTransaction();

        if (savedInstanceState == null) {
            homeFragment = new HomeFragment();
            testFragment = new TestFragment();
            ft.add(R.id.fragment_container, homeFragment, TAG_HOME)
                    .add(R.id.fragment_container, testFragment, TAG_TEST)
                    .hide(testFragment)
                    .commit();
        } else {
            homeFragment = (HomeFragment) fm.findFragmentByTag(TAG_HOME);
            testFragment = (TestFragment) fm.findFragmentByTag(TAG_TEST);
            FragmentTransaction restoreFt = fm.beginTransaction();
            boolean transactionNeeded = false;

            if (homeFragment == null) {
                homeFragment = new HomeFragment();
                restoreFt.add(R.id.fragment_container, homeFragment, TAG_HOME);
                transactionNeeded = true;
            }
            if (testFragment == null) {
                testFragment = new TestFragment();
                restoreFt.add(R.id.fragment_container, testFragment, TAG_TEST);
                transactionNeeded = true;
            }

            BottomNavigationView navView = findViewById(R.id.bottom_nav);
            int selectedItemId = navView.getSelectedItemId();

            if (selectedItemId == R.id.nav_empty && testFragment != null) {
                if (homeFragment != null && homeFragment.isAdded() && !homeFragment.isHidden())
                    restoreFt.hide(homeFragment);
                if (!testFragment.isAdded()) {
                    if (fm.findFragmentByTag(TAG_TEST) == null)
                        restoreFt.add(R.id.fragment_container, testFragment, TAG_TEST);
                }
                restoreFt.show(testFragment);
            } else if (homeFragment != null) {
                if (testFragment != null && testFragment.isAdded() && !testFragment.isHidden())
                    restoreFt.hide(testFragment);
                if (!homeFragment.isAdded()) {
                    if (fm.findFragmentByTag(TAG_HOME) == null)
                        restoreFt.add(R.id.fragment_container, homeFragment, TAG_HOME);
                }
                restoreFt.show(homeFragment);
            }
            if (transactionNeeded || !restoreFt.isEmpty()) restoreFt.commitAllowingStateLoss();
        }

        BottomNavigationView nav = findViewById(R.id.bottom_nav);
        nav.setOnItemSelectedListener(item -> {
            if (homeFragment == null || testFragment == null || !homeFragment.isAdded() || !testFragment.isAdded()) {
                recreateFragmentsIfNeeded(item.getItemId());
            }
            FragmentTransaction txn = getSupportFragmentManager().beginTransaction();
            int id = item.getItemId();
            if (id == R.id.nav_home) {
                if (homeFragment != null && homeFragment.isAdded()) txn.show(homeFragment);
                else return false;
                if (testFragment != null && testFragment.isAdded()) txn.hide(testFragment);
            } else if (id == R.id.nav_empty) {
                if (testFragment != null && testFragment.isAdded()) txn.show(testFragment);
                else return false;
                if (homeFragment != null && homeFragment.isAdded()) txn.hide(homeFragment);
            } else {
                return false;
            }
            txn.commitAllowingStateLoss();
            return true;
        });
    }

    private void recreateFragmentsIfNeeded(int selectedNavId) {
        // ... (此方法保持您之前的版本) ...
        FragmentManager fm = getSupportFragmentManager();
        FragmentTransaction ft = null;
        boolean homeNeedsHandling = homeFragment == null || !homeFragment.isAdded();
        boolean testNeedsHandling = testFragment == null || !testFragment.isAdded();
        if (homeNeedsHandling) {
            homeFragment = (HomeFragment) fm.findFragmentByTag(TAG_HOME);
            if (homeFragment == null) homeFragment = new HomeFragment();
            if (ft == null) ft = fm.beginTransaction();
            if (!homeFragment.isAdded()) ft.add(R.id.fragment_container, homeFragment, TAG_HOME);
        }
        if (testNeedsHandling) {
            testFragment = (TestFragment) fm.findFragmentByTag(TAG_TEST);
            if (testFragment == null) testFragment = new TestFragment();
            if (ft == null) ft = fm.beginTransaction();
            if (!testFragment.isAdded()) ft.add(R.id.fragment_container, testFragment, TAG_TEST);
        }
        if (ft != null) {
            if (selectedNavId == R.id.nav_empty) {
                if (testFragment != null) ft.show(testFragment);
                if (homeFragment != null) ft.hide(homeFragment);
            } else {
                if (homeFragment != null) ft.show(homeFragment);
                if (testFragment != null) ft.hide(testFragment);
            }
            ft.commitAllowingStateLoss();
        }
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
    }
}
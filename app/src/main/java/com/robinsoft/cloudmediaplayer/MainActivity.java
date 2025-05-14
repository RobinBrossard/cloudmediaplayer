package com.robinsoft.cloudmediaplayer;

import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.FragmentTransaction;
import androidx.media3.common.util.UnstableApi;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.robinsoft.cloudmediaplayer.fragment.HomeFragment;
import com.robinsoft.cloudmediaplayer.fragment.TestFragment;

/**
 * 主界面，包含 HomeFragment 与 TestFragment 的切换
 */
@UnstableApi
public class MainActivity extends AppCompatActivity {
    private static final String TAG_HOME = "home";
    private static final String TAG_TEST = "test_fr";

    // 由于 HomeFragment/TestFragment 上都标记了 @UnstableApi，
    // 这里需要在使用处或类上 opt-in。本例在类上加了 @UnstableApi。
    private HomeFragment homeFragment;
    private TestFragment testFragment;

    @UnstableApi // 也可以只在方法上标注
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);  // 包含 fragment_container 与 bottom_nav

        FragmentTransaction ft = getSupportFragmentManager().beginTransaction();

        // 如果是首次创建，就 add 两个 Fragment，并隐藏 TestFragment
        if (savedInstanceState == null) {
            homeFragment = new HomeFragment();
            testFragment = new TestFragment();
            ft.add(R.id.fragment_container, homeFragment, TAG_HOME)
                    .add(R.id.fragment_container, testFragment, TAG_TEST)
                    .hide(testFragment)
                    .commit();
        } else {
            // Activity 重建，恢复已有实例
            homeFragment = (HomeFragment) getSupportFragmentManager().findFragmentByTag(TAG_HOME);
            testFragment = (TestFragment) getSupportFragmentManager().findFragmentByTag(TAG_TEST);
            // 确保显示 homeFragment、隐藏 testFragment
            ft.hide(testFragment)
                    .show(homeFragment)
                    .commit();
        }

        BottomNavigationView nav = findViewById(R.id.bottom_nav);
        nav.setOnItemSelectedListener(item -> {
            FragmentTransaction txn = getSupportFragmentManager().beginTransaction();
            int id = item.getItemId();
            if (id == R.id.nav_home) {
                txn.show(homeFragment)
                        .hide(testFragment);
            } else if (id == R.id.nav_empty) {
                txn.show(testFragment)
                        .hide(homeFragment);
            } else {
                return false;
            }
            txn.commit();
            return true;
        });
    }
}

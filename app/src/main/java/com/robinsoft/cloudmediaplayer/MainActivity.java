package com.robinsoft.cloudmediaplayer;

import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.FragmentTransaction;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.robinsoft.cloudmediaplayer.fragment.EmptyFragment;
import com.robinsoft.cloudmediaplayer.fragment.HomeFragment;

public class MainActivity extends AppCompatActivity {
    private static final String TAG_HOME  = "home";
    private static final String TAG_EMPTY = "empty";

    private HomeFragment  homeFragment;
    private EmptyFragment emptyFragment;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);  // 包含 fragment_container 与 bottom_nav

        FragmentTransaction ft = getSupportFragmentManager().beginTransaction();

        // 如果是首次创建，就 add 两个 Fragment，并隐藏 empty
        if (savedInstanceState == null) {
            homeFragment  = new HomeFragment();
            emptyFragment = new EmptyFragment();
            ft.add(R.id.fragment_container, homeFragment, TAG_HOME)
                    .add(R.id.fragment_container, emptyFragment, TAG_EMPTY)
                    .hide(emptyFragment)
                    .commit();
        } else {
            // Activity 重建，取回已有实例
            homeFragment  = (HomeFragment)  getSupportFragmentManager().findFragmentByTag(TAG_HOME);
            emptyFragment = (EmptyFragment) getSupportFragmentManager().findFragmentByTag(TAG_EMPTY);
            // 确保初始显示 home
            assert emptyFragment != null;
            ft.hide(emptyFragment)
                    .show(homeFragment)
                    .commit();
        }

        BottomNavigationView nav = findViewById(R.id.bottom_nav);
        nav.setOnItemSelectedListener(item -> {
            FragmentTransaction txn = getSupportFragmentManager().beginTransaction();
            int id = item.getItemId();
            if (id == R.id.nav_home) {
                txn.show(homeFragment)
                        .hide(emptyFragment);
            } else if (id == R.id.nav_empty) {
                txn.show(emptyFragment)
                        .hide(homeFragment);
            } else {
                return false;
            }
            txn.commit();
            return true;
        });
    }
}

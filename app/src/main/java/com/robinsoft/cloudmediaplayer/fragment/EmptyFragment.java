package com.robinsoft.cloudmediaplayer.fragment;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.robinsoft.cloudmediaplayer.R;

public class EmptyFragment extends Fragment {

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        // 将 fragment_empty.xml 布局 inflate 进来
        return inflater.inflate(R.layout.fragment_empty, container, false);
    }
}

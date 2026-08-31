package com.syu.ipc;

import com.syu.ipc.IModuleCallback;
import com.syu.ipc.ModuleObject;

interface IRemoteModule {
    void cmd(int cmdCode, in int[] ints, in float[] flts, in String[] strs);

    com.syu.ipc.ModuleObject get(int getCode, in int[] ints, in float[] flts, in String[] strs);

    void register(com.syu.ipc.IModuleCallback cb, int code, int enable);

    void unregister(com.syu.ipc.IModuleCallback cb, int code);
}

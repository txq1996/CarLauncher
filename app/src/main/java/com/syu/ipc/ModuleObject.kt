package com.syu.ipc

import android.os.Parcel
import android.os.Parcelable

/**
 * com.syu.ms 工具包 AIDL 的标准数据容器。
 *
 * 对应 AIDL 文件 `com/syu/ipc/ModuleObject.aidl`，由 `aidl` 编译生成
 * Proxy/Stub 后在 binder 事务中传输。wire 格式固定为
 * `writeIntArray → writeFloatArray → writeStringArray`，调用方按需填充对应字段，
 * 其它字段保持 `null`。
 *
 * 三种典型构造：
 * - [ModuleObject]：默认构造，调用方手动填充三个数组
 * - [ModuleObject] 接收 `int`：纯整数（车速 IPC 主要走这个）
 * - [ModuleObject] 接收 `String`：纯字符串
 *
 * **wire 兼容性**：AIDL 编译器按字段顺序生成 `writeToParcel`，
 * 任何字段顺序/类型调整都会破坏与 `com.syu.ms` 的兼容，故禁止重排字段。
 */
class ModuleObject : Parcelable {
    /** 整型数组（如车速、ACC 状态等数值） */
    @JvmField var ints: IntArray? = null

    /** 浮点数组（预留字段，目前未启用） */
    @JvmField var flts: FloatArray? = null

    /** 字符串数组（预留字段，目前未启用） */
    @JvmField var strs: Array<String>? = null

    /** 默认构造，调用方手动填充三个数组 */
    constructor()

    /** 单整数便捷构造：`ints = intArrayOf(value)` */
    constructor(value: Int) {
        ints = intArrayOf(value)
    }

    /** 单字符串便捷构造：`strs = arrayOf(value)` */
    constructor(value: String) {
        strs = arrayOf(value)
    }

    protected constructor(`in`: Parcel) {
        ints = `in`.createIntArray()
        flts = `in`.createFloatArray()
        strs = `in`.createStringArray()
    }

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeIntArray(ints)
        dest.writeFloatArray(flts)
        dest.writeStringArray(strs)
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<ModuleObject> {
        override fun createFromParcel(source: Parcel): ModuleObject = ModuleObject(source)
        override fun newArray(size: Int): Array<ModuleObject?> = arrayOfNulls(size)
    }
}

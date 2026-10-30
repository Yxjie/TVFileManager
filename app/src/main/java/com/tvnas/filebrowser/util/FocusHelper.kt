package com.tvnas.filebrowser.util

import android.app.Activity
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import com.tvnas.filebrowser.R

/**
 * 电视端 RecyclerView 焦点恢复辅助类
 */
object FocusHelper {

    private val TAG_ATTACH_LISTENER = R.id.focus_helper_listener
    private val TAG_RETRY_RUNNABLE = R.id.focus_helper_runnable

    /**
     * 恢复并请求指定位置的焦点（支持 ChildAttach 监听 + 10次 500ms 轮询兜底，专为低端 TV 盒子优化）
     * 每次恢复前清理同一 RecyclerView 上未完成的旧调用的监听器与轮询，防止连续恢复/快速切目录时旧回调误抢焦点
     */
    fun restoreAndRequestFocus(
        recyclerView: RecyclerView,
        targetPosition: Int,
        activity: Activity,
        maxRetries: Int = 10
    ) {
        // 无论走哪条分支，先清理上一次未完成的恢复任务，保证同一 RecyclerView 同一时刻至多一个待恢复请求
        clearPendingFocusRequests(recyclerView)

        if (activity.isFinishing || activity.isDestroyed) return
        val adapter = recyclerView.adapter ?: return
        if (targetPosition !in 0 until adapter.itemCount) return

        val viewHolder = recyclerView.findViewHolderForAdapterPosition(targetPosition)
        if (viewHolder != null) {
            if (!viewHolder.itemView.hasFocus()) {
                viewHolder.itemView.requestFocus()
            }
            return
        }

        val lm = recyclerView.layoutManager ?: return
        lm.scrollToPosition(targetPosition)

        // 绑定 ChildAttach 监听器：低端 TV 盒子异步布局完成后视图一上屏立刻抢焦点
        val attachListener = object : RecyclerView.OnChildAttachStateChangeListener {
            override fun onChildViewAttachedToWindow(view: View) {
                val pos = recyclerView.getChildAdapterPosition(view)
                if (pos == targetPosition) {
                    if (!view.hasFocus()) {
                        view.requestFocus()
                    }
                    clearPendingFocusRequests(recyclerView)
                }
            }

            override fun onChildViewDetachedFromWindow(view: View) {}
        }

        recyclerView.setTag(TAG_ATTACH_LISTENER, attachListener)
        recyclerView.addOnChildAttachStateChangeListener(attachListener)

        // 定时轮询兜底（10 * 50ms = 500ms 窗口）
        var retriesLeft = maxRetries
        val retryRunnable = object : Runnable {
            override fun run() {
                if (activity.isFinishing || activity.isDestroyed) {
                    clearPendingFocusRequests(recyclerView)
                    return
                }
                val vh = recyclerView.findViewHolderForAdapterPosition(targetPosition)
                if (vh != null) {
                    if (!vh.itemView.hasFocus()) {
                        vh.itemView.requestFocus()
                    }
                    clearPendingFocusRequests(recyclerView)
                } else if (retriesLeft > 0) {
                    retriesLeft--
                    recyclerView.postDelayed(this, 50)
                } else {
                    clearPendingFocusRequests(recyclerView)
                }
            }
        }

        recyclerView.setTag(TAG_RETRY_RUNNABLE, retryRunnable)
        recyclerView.postDelayed(retryRunnable, 50)
    }

    private fun clearPendingFocusRequests(recyclerView: RecyclerView) {
        (recyclerView.getTag(TAG_ATTACH_LISTENER) as? RecyclerView.OnChildAttachStateChangeListener)?.let {
            recyclerView.removeOnChildAttachStateChangeListener(it)
            recyclerView.setTag(TAG_ATTACH_LISTENER, null)
        }
        (recyclerView.getTag(TAG_RETRY_RUNNABLE) as? Runnable)?.let {
            recyclerView.removeCallbacks(it)
            recyclerView.setTag(TAG_RETRY_RUNNABLE, null)
        }
    }
}

package com.blanke.mdwechat.ui

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.support.v4.content.ContextCompat
import android.util.SparseArray
import android.view.*
import android.widget.AdapterView
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RelativeLayout
import com.blanke.mdwechat.R
import com.blanke.mdwechat.WeChatHelper.wxConfig
import com.blanke.mdwechat.WeChatHelper.xClass
import com.blanke.mdwechat.WeChatHelper.xMethod
import com.blanke.mdwechat.config.C
import com.blanke.mdwechat.config.HookConfig
import com.blanke.mdwechat.config.WxClass
import com.blanke.mdwechat.config.WxObjects
import com.blanke.mdwechat.util.ConvertUtils
import com.blanke.mdwechat.widget.MaterialSearchView
import com.flyco.tablayout.CommonTabLayout
import com.flyco.tablayout.listener.CustomTabEntity
import com.flyco.tablayout.listener.OnTabSelectListener
import com.github.clans.fab.FloatingActionButton
import com.github.clans.fab.FloatingActionButton.SIZE_MINI
import com.github.clans.fab.FloatingActionMenu
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.XposedHelpers.callMethod
import de.robv.android.xposed.XposedHelpers.getObjectField
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.ref.WeakReference
import java.util.*

/**
 * Created by blanke on 2017/8/1.
 */

class MainHook : BaseHookUi() {
    private var searchView: WeakReference<MaterialSearchView>? = null
    private var searchKey: String? = null
    private var actionMenu: WeakReference<FloatingActionMenu>? = null
    private var hookPopWindowAdapter: WeakReference<AdapterView.OnItemClickListener>? = null

    override fun hook(lpparam: XC_LoadPackage.LoadPackageParam) {
        hookWechatMain(lpparam)
    }

    private fun hookWechatMain(lpparam: XC_LoadPackage.LoadPackageParam) {
        xMethod(WxClass.LauncherUI!!,
                wxConfig.methods.LauncherUI_startMainUI,
                object : XC_MethodHook() {
                    @Throws(Throwable::class)
                    override fun afterHookedMethod(param: XC_MethodHook.MethodHookParam) {
                        val activity = param.thisObject as Activity
                        log("LauncherUI_startMainUI LauncherUI=" + activity)
                        val isMainInit = XposedHelpers.getAdditionalInstanceField(activity, KEY_ISMAININIT)
                        if (isMainInit != null && isMainInit as Boolean) {
                            return
                        }
                        log("LauncherUI_startMainUI addView")
                        refreshPrefs()
                        //fix soft input ui move up
                        activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN)
                        WxObjects.LauncherUI = WeakReference(activity)
                        val mHomeUi = getObjectField(activity, wxConfig.fields.LauncherUI_mHomeUi)
                        WxObjects.HomeUI = WeakReference(mHomeUi)

                        val mHomeUiTabHelper = XposedHelpers.getObjectField(mHomeUi, wxConfig.fields.HomeUi_mHomeUiTabHelper)
                        WxObjects.HomeUiTabHelper = WeakReference(mHomeUiTabHelper)

                        val viewPager = XposedHelpers.getObjectField(mHomeUiTabHelper, wxConfig.fields.HomeUiTabHelper_mViewPager)
                        WxObjects.HomeUI_ViewPager = WeakReference(viewPager)

                        if (viewPager !is View) {
                            log("HomeUI_ViewPager not is View,MainHook fail!")
                            return
                        }
                        val linearLayoutContent = viewPager.parent as ViewGroup
                        //移除底部 tabview
                        val tabView = linearLayoutContent.getChildAt(1)
                        if (tabView == null || tabView !is RelativeLayout) {
                            log("HomeUI_tabView not found,MainHook fail!")
                            return
                        }
                        WxObjects.LauncherUIBottomTabView = WeakReference(tabView)
                        if (HookConfig.isHooktab) {
                            linearLayoutContent.removeView(tabView)
                        }
                        //add float Button
                        val contentFrameLayout = linearLayoutContent.parent as ViewGroup
                        if (HookConfig.isHookfloat_button) {
                            addFloatButton(contentFrameLayout)

                            // hook floatbutton click
                            if (mHomeUi != null) {
                                val popWindowAdapter = XposedHelpers.getObjectField(mHomeUi, wxConfig.fields.HomeUI_mMenuAdapterManager)
                                if (popWindowAdapter != null) {
                                    val hookPopWindowAdapter = popWindowAdapter as AdapterView.OnItemClickListener
                                    val hookArrays = SparseArray<Any>()
                                    val mapping = intArrayOf(10, 20, 2, 1)
//                                  log("mapping:" + Arrays.toString(mapping));
                                    val mMenuItemViewHolder = xClass(wxConfig.classes.MenuItemViewHolder)
                                    val mMenuItemViewHolderWrapper = xClass(wxConfig.classes.MenuItemViewHolderWrapper)
                                    val dConstructor = mMenuItemViewHolder.getConstructor(C.Int, C.String, C.String, C.Int, C.Int)
                                    val cConstructor = mMenuItemViewHolderWrapper.getConstructor(mMenuItemViewHolder)
                                    for (i in mapping.indices) {
                                        val d = dConstructor.newInstance(mapping[i], "", "", mapping[i], mapping[i])
                                        val c = cConstructor.newInstance(d)
                                        hookArrays.put(i, c)
                                    }
                                    //                              log("hookArrays=" + hookArrays);
                                    XposedHelpers.setObjectField(popWindowAdapter, wxConfig.fields.MenuAdapterManager_mMenuArray, hookArrays)
                                    this@MainHook.hookPopWindowAdapter = WeakReference(hookPopWindowAdapter)
                                }
                            }
                        }
                        /******************
                         * hook SearchView
                         */
                        if (HookConfig.isHooksearch) {
                            val actionLayout = findViewByIdName(activity, wxConfig.views.ActionBarContainer) as ViewGroup
                            addSearchView(actionLayout, lpparam)
                            // hook click search
                            xMethod(WxClass.LauncherUI!!,
                                    "onOptionsItemSelected",
                                    C.MenuItem,
                                    object : XC_MethodHook() {
                                        @Throws(Throwable::class)
                                        override fun beforeHookedMethod(param: XC_MethodHook.MethodHookParam?) {
                                            val menuItem = param!!.args[0] as MenuItem
                                            if (menuItem.itemId == 1) {//search
                                                //log("hook launcherUi click action search icon success");
                                                searchView?.get()?.show()
                                                param.result = true
                                            }
                                        }
                                    })
                        }
                        /******************
                         * hook tabLayout
                         */
                        if (HookConfig.isHooktab) {
                            addTabLayout(linearLayoutContent, viewPager)
                        }

                        XposedHelpers.setAdditionalInstanceField(activity, KEY_ISMAININIT, true)
                    }
                })
        if (HookConfig.isHookfloat_button) {
            //hide more icon in actionBar
            xMethod(WxClass.LauncherUI!!, "onCreateOptionsMenu", C.Menu, object : XC_MethodHook() {
                @Throws(Throwable::class)
                override fun afterHookedMethod(param: XC_MethodHook.MethodHookParam?) {
                    //                log("homeUI=" + mHomeUi);
                    val mHomeUi = WxObjects.HomeUI?.get()
                    if (mHomeUi != null) {
                        //hide actionbar more add icon
                        val moreMenuItem = getObjectField(mHomeUi, wxConfig.fields.HomeUI_mMoreMenuItem) as MenuItem
                        //log("moreMenuItem=" + moreMenuItem);
                        moreMenuItem.isVisible = false
                    }
                }
            })
        }
        if (HookConfig.isHooksearch || HookConfig.isHookfloat_button) {
            //hook onKeyEvent
            xMethod(WxClass.LauncherUI!!, "dispatchKeyEvent", C.KeyEvent, object : XC_MethodHook() {
                @Throws(Throwable::class)
                override fun beforeHookedMethod(param: XC_MethodHook.MethodHookParam) {
                    val keyEvent = param.args[0] as KeyEvent
                    if (keyEvent.keyCode == KeyEvent.KEYCODE_MENU) {//hide menu
                        param.result = true
                        return
                    }
                    if (keyEvent.keyCode == KeyEvent.KEYCODE_BACK) {//hook back
                        if (searchView?.get() == null) {
                            return
                        }
                        if (searchView!!.get()!!.isSearchViewVisible) {
                            searchView?.get()?.hide()
                            param.result = true
                            return
                        }
                        if (actionMenu?.get() == null) {
                            return
                        }
                        if (actionMenu!!.get()!!.isOpened) {
                            actionMenu?.get()?.close(true)
                            param.result = true
                            return
                        }
                    }
                }
            })

        }
    }

    private fun addFloatButton(frameLayout: ViewGroup) {
        val primaryColor = colorPrimary
        val context = WxObjects.MdContext?.get() as Context
        val actionMenu = FloatingActionMenu(context)
        actionMenu.menuButtonColorNormal = primaryColor
        actionMenu.menuButtonColorPressed = primaryColor
        actionMenu.setMenuIcon(ContextCompat.getDrawable(context, R.drawable.ic_add))
        actionMenu.initMenuButton()

        actionMenu.setFloatButtonClickListener { fab, index ->
            //log("click fab,index=" + index + ",label" + fab.getLabelText());
            if (hookPopWindowAdapter?.get() != null) {
                hookPopWindowAdapter?.get()?.onItemClick(null, null, index, 0)
            }
        }

        addFloatButton(actionMenu, context, context.getString(R.string.text_scan_qrcode),
                R.drawable.ic_scan, primaryColor)
        addFloatButton(actionMenu, context, context.getString(R.string.text_money),
                R.drawable.ic_money, primaryColor)
        addFloatButton(actionMenu, context, context.getString(R.string.text_chat_group),
                R.drawable.ic_chat, primaryColor)
        addFloatButton(actionMenu, context, context.getString(R.string.text_friend_add),
                R.drawable.ic_friend_add, primaryColor)

        val params = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        val margin = ConvertUtils.dp2px(frameLayout.context, 12f)
        params.rightMargin = margin
        params.bottomMargin = margin
        params.gravity = Gravity.END or Gravity.BOTTOM
        actionMenu.menuButton.setOnTouchListener(object : View.OnTouchListener {
            internal var lastX: Float = 0.toFloat()
            internal var lastY: Float = 0.toFloat()
            internal var downTime: Long = 0

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                if (event.action == MotionEvent.ACTION_DOWN) {
                    lastX = event.rawX
                    lastY = event.rawY
                    downTime = System.currentTimeMillis()
                } else if (event.action == MotionEvent.ACTION_MOVE) {
                    val nowX = event.rawX
                    val nowY = event.rawY
                    val dx = (nowX - lastX).toInt().toFloat()
                    val dy = (nowY - lastY).toInt().toFloat()
                    lastX = nowX
                    lastY = nowY
                    actionMenu.x = actionMenu.x + dx
                    actionMenu.y = actionMenu.y + dy
                } else if (event.action == MotionEvent.ACTION_UP) {
                    val nowTime = System.currentTimeMillis()
                    if (nowTime - downTime > 300) {
                        actionMenu.menuButton.isPressed = false
                        return true
                    }
                }
                return false
            }
        })


        val backgroundView = View(context)
        backgroundView.setBackgroundColor(Color.parseColor("#88000000"))
        val params2 = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        backgroundView.visibility = View.GONE
        backgroundView.setOnClickListener { view ->
            actionMenu.close(true)
            view.visibility = View.GONE
        }
        actionMenu.setOnMenuToggleListener { opened -> backgroundView.visibility = if (opened) View.VISIBLE else View.GONE }
        frameLayout.addView(backgroundView, params2)
        frameLayout.addView(actionMenu, params)
        this.actionMenu = WeakReference(actionMenu)
    }

    private fun addFloatButton(actionMenu: FloatingActionMenu, context: Context,
                               label: String, drawable: Int, primaryColor: Int): FloatingActionButton {
        val fab = FloatingActionButton(context)
        fab.setImageDrawable(ContextCompat.getDrawable(context, drawable))
        fab.colorNormal = primaryColor
        fab.colorPressed = primaryColor
        fab.buttonSize = SIZE_MINI
        fab.labelText = label
        actionMenu.addMenuButton(fab)
        fab.setLabelColors(primaryColor, primaryColor, primaryColor)
        return fab
    }

    private fun addSearchView(frameLayout: ViewGroup, lpparam: XC_LoadPackage.LoadPackageParam) {
        if (WxObjects.LauncherUI?.get() == null) {
            return
        }
        val context = WxObjects.MdContext?.get() as Context
        val searchView = MaterialSearchView(context)
        searchView.setOnSearchListener(object : MaterialSearchView.SimpleonSearchListener() {
            override fun onSearch(query: String) {
                searchKey = query
                val mHomeUi = WxObjects.HomeUI?.get()
                if (mHomeUi != null) {
                    XposedHelpers.callMethod(mHomeUi, wxConfig.methods.HomeUI_startSearch)
                }
                searchView.hide()
            }
        })
        val params = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        frameLayout.addView(searchView, params)
//        findAndHookConstructor(wxConfig.classes.ActionBarEditText,
//                lpparam.classLoader, C.Context, C.AttributeSet,
//                object : XC_MethodHook() {
//                    @Throws(Throwable::class)
//                    override fun afterHookedMethod(param: XC_MethodHook.MethodHookParam?) {
//                        if (!TextUtils.isEmpty(searchKey)) {
//                            val editText = param!!.thisObject as EditText
//                            editText.postDelayed({
//                                editText.setText(searchKey)
//                                searchKey = null
//                            }, 300)
//                        }
//                    }
//                })
        this.searchView = WeakReference(searchView)
    }

    private fun addTabLayout(viewPagerLinearLayout: ViewGroup, pager: View) {
        if (WxObjects.LauncherUI?.get() == null) {
            return
        }
        val context = WxObjects.MdContext?.get() as Context
        val tabLayout = CommonTabLayout(context)
        val primaryColor = colorPrimary
        tabLayout.setBackgroundColor(primaryColor)
        tabLayout.textSelectColor = Color.WHITE
        tabLayout.textUnselectColor = 0x1acccccc
        val dp2 = ConvertUtils.dp2px(pager.context, 1f)
        tabLayout.indicatorHeight = dp2.toFloat()
        tabLayout.indicatorColor = Color.WHITE
        tabLayout.indicatorCornerRadius = dp2.toFloat()
        tabLayout.indicatorAnimDuration = 200
        tabLayout.elevation = 5f
        tabLayout.textsize = context.getResources().getDimension(R.dimen.tabTextSize)
        tabLayout.unreadBackground = Color.WHITE
        tabLayout.unreadTextColor = primaryColor
        tabLayout.selectIconColor = Color.WHITE
        tabLayout.unSelectIconColor = 0x1aaaaaaa

        //        String[] titles = {"消息", "通讯录", "朋友圈", "设置"};
        val tabIcons = intArrayOf(R.drawable.ic_chat_tab, R.drawable.ic_contact_tab, R.drawable.ic_explore_tab, R.drawable.ic_person_tab)
        val mTabEntities = tabIcons.indices
                .map { it }
                .mapTo(ArrayList<CustomTabEntity>()) {
                    object : CustomTabEntity {
                        override fun getTabTitle(): String? {
                            return null
                        }

                        override fun getTabSelectedIcon(): Int {
                            return tabIcons[it]
                        }

                        override fun getTabUnselectedIcon(): Int {
                            return 0
                        }
                    }
                }
        tabLayout.setTabData(mTabEntities)
        tabLayout.setOnTabSelectListener(object : OnTabSelectListener {
            override fun onTabSelect(position: Int) {
                //log("tab click position=" + position);
                callMethod(pager, wxConfig.methods.WxViewPager_setCurrentItem, position)
            }

            override fun onTabReselect(position: Int) {
                //TODO goto unread msg
            }
        })
        xMethod(WxClass.HomeUiViewPagerChangeListener!!,
                wxConfig.methods.HomeUiViewPagerChangeListener_onPageScrolled,
                C.Int, C.Float, C.Int, object : XC_MethodHook() {
            @Throws(Throwable::class)
            override fun beforeHookedMethod(param: XC_MethodHook.MethodHookParam?) {
                val positionOffset = param!!.args[1] as Float
                val position = param.args[0] as Int
                tabLayout.startScrollPosition = position
                tabLayout.indicatorOffset = positionOffset
            }
        })
        xMethod(WxClass.HomeUiViewPagerChangeListener!!,
                wxConfig.methods.HomeUiViewPagerChangeListener_onPageSelected,
                C.Int, object : XC_MethodHook() {
            @Throws(Throwable::class)
            override fun beforeHookedMethod(param: XC_MethodHook.MethodHookParam?) {
                val position = param!!.args[0] as Int
                tabLayout.currentTab = position
            }
        })

        xMethod(WxClass.LauncherUIBottomTabView!!,
                wxConfig.methods.LauncherUIBottomTabView_setMainTabUnread,
                C.Int, object : XC_MethodHook() {
            @Throws(Throwable::class)
            override fun beforeHookedMethod(param: XC_MethodHook.MethodHookParam?) {
                //log("msg unread=" + param.args[0]);
                tabLayout.showMsg(0, param!!.args[0] as Int)
            }
        })
        xMethod(WxClass.LauncherUIBottomTabView!!,
                wxConfig.methods.LauncherUIBottomTabView_setContactTabUnread,
                C.Int, object : XC_MethodHook() {
            @Throws(Throwable::class)
            override fun beforeHookedMethod(param: XC_MethodHook.MethodHookParam?) {
                //log("contact unread=" + param.args[0]);
                tabLayout.showMsg(1, param!!.args[0] as Int)
            }
        })
        xMethod(WxClass.LauncherUIBottomTabView!!,
                wxConfig.methods.LauncherUIBottomTabView_setFriendTabUnread,
                C.Int, object : XC_MethodHook() {
            @Throws(Throwable::class)
            override fun beforeHookedMethod(param: XC_MethodHook.MethodHookParam?) {
                //log("friend unread=" + param.args[0]);
                tabLayout.showMsg(2, param!!.args[0] as Int)
            }
        })
        xMethod(WxClass.LauncherUIBottomTabView!!,
                wxConfig.methods.LauncherUIBottomTabView_showFriendTabUnreadPoint,
                C.Boolean, object : XC_MethodHook() {
            @Throws(Throwable::class)
            override fun beforeHookedMethod(param: XC_MethodHook.MethodHookParam?) {
                val show = param!!.args[0] as Boolean
                tabLayout.showMsg(2, if (show) -1 else 0)
            }
        })

        //settings unread ignore
        //        xMethod(wxConfig.classes.LauncherUIBottomTabView,
        //                "yD",
        //                int.class, new XC_MethodHook() {
        //                    @Override
        //                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
        //                        log("yd unread=" + param.args[0]);
        //                    }
        //                });

        val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        params.height = ConvertUtils.dp2px(viewPagerLinearLayout.context, 48f)
        viewPagerLinearLayout.addView(tabLayout, 0, params)
    }

    companion object {
        private val KEY_ISMAININIT = "KEY_ISMAININIT"
    }
}

package com.github.kevin58cmd.customkotlinxandroidsynthetic

import com.github.kevin58cmd.customkotlinxandroidsynthetic.migration.ButterKnifeMigration
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.rules.TemporaryFolder

class ButterKnifeMigrationTest : BasePlatformTestCase() {
    private val stubs = linkedMapOf<String, String>()
    private fun addStub(source: String): PsiJavaFile {
        val pkg = source.substringAfter("package ").substringBefore(';')
        val name = Regex("(?:class|interface) ([A-Za-z0-9_]+)").find(source)!!.groupValues[1]
        val path = pkg.replace('.', '/') + "/$name.java"
        stubs[path] = source
        return myFixture.addFileToProject(path, source) as PsiJavaFile
    }
    override fun setUp() {
        super.setUp()
        addStub("""package android.view; public class View {
            public static final int NO_ID = -1;
            public java.util.Map<Integer, View> children = new java.util.HashMap<Integer, View>();
            public <T extends View> T findViewById(int id) { return (T) children.get(id); }
            public android.content.Context getContext() { return new android.content.Context(); }
            public android.content.res.Resources getResources() { return getContext().getResources(); }
            public OnClickListener click; public OnLongClickListener longClick; public OnTouchListener touch; public OnFocusChangeListener focus;
            public void setOnClickListener(OnClickListener l) { click=l; }
            public void setOnLongClickListener(OnLongClickListener l) { longClick=l; }
            public void setOnTouchListener(OnTouchListener l) { touch=l; }
            public void setOnFocusChangeListener(OnFocusChangeListener l) { focus=l; }
            public interface OnClickListener { void onClick(View v); }
            public interface OnLongClickListener { boolean onLongClick(View v); }
            public interface OnTouchListener { boolean onTouch(View v, MotionEvent e); }
            public interface OnFocusChangeListener { void onFocusChange(View v, boolean b); }
        }""")
        addStub("package android.view; public class MotionEvent {}")
        addStub("package android.view; public class KeyEvent {}")
        addStub("package android.view; public class Window { public View decor = new View(); public View getDecorView() { return decor; } }")
        addStub("package android.os; public class Bundle {}")
        addStub("package android.app; public class Activity { public android.view.Window window = new android.view.Window(); public android.view.Window getWindow() { return window; } public void onCreate(android.os.Bundle state) {} public void onDestroy() {} public void setContentView(int layout) {} }")
        addStub("package android.app; public class Dialog { public android.view.Window window = new android.view.Window(); public android.view.Window getWindow() { return window; } }")
        addStub("""package android.widget; public class TextView extends android.view.View {
            public java.util.List<android.text.TextWatcher> watchers = new java.util.ArrayList<android.text.TextWatcher>();
            public void addTextChangedListener(android.text.TextWatcher w) { watchers.add(w); }
            public void removeTextChangedListener(android.text.TextWatcher w) { watchers.remove(w); }
            public OnEditorActionListener editor; public void setOnEditorActionListener(OnEditorActionListener l) { editor=l; }
            public interface OnEditorActionListener { boolean onEditorAction(TextView v, int action, android.view.KeyEvent event); }
        }""")
        addStub("package android.widget; public class CompoundButton extends TextView { public OnCheckedChangeListener checked; public void setOnCheckedChangeListener(OnCheckedChangeListener l) { checked=l; } public interface OnCheckedChangeListener { void onCheckedChanged(CompoundButton b, boolean c); } }")
        addStub("""package android.widget; public class AdapterView<T> extends android.view.View {
            public OnItemClickListener item; public OnItemLongClickListener longItem; public OnItemSelectedListener selected;
            public void setOnItemClickListener(OnItemClickListener l) { item=l; }
            public void setOnItemLongClickListener(OnItemLongClickListener l) { longItem=l; }
            public void setOnItemSelectedListener(OnItemSelectedListener l) { selected=l; }
            public interface OnItemClickListener { void onItemClick(AdapterView<?> a, android.view.View v, int p, long id); }
            public interface OnItemLongClickListener { boolean onItemLongClick(AdapterView<?> a, android.view.View v, int p, long id); }
            public interface OnItemSelectedListener { void onItemSelected(AdapterView<?> a, android.view.View v, int p, long id); void onNothingSelected(AdapterView<?> a); }
        }""")
        addStub("package android.text; public interface Editable extends java.lang.CharSequence {}")
        addStub("package android.text; public interface TextWatcher { void beforeTextChanged(CharSequence s, int start, int count, int after); void onTextChanged(CharSequence s, int start, int before, int count); void afterTextChanged(Editable e); }")
        addStub("package androidx.viewpager.widget; public class ViewPager extends android.view.View { public java.util.List<OnPageChangeListener> listeners = new java.util.ArrayList<OnPageChangeListener>(); public void addOnPageChangeListener(OnPageChangeListener l) { listeners.add(l); } public void removeOnPageChangeListener(OnPageChangeListener l) { listeners.remove(l); } public interface OnPageChangeListener { void onPageSelected(int p); void onPageScrolled(int p, float offset, int pixels); void onPageScrollStateChanged(int state); } }")
        addStub("package android.graphics; public class Bitmap {}")
        addStub("package android.graphics; public class BitmapFactory { public static Bitmap decodeResource(android.content.res.Resources r, int id) { return new Bitmap(); } }")
        addStub("package android.graphics.drawable; public class Drawable { public int tint; public Drawable mutate() { return this; } }")
        addStub("package androidx.core.graphics.drawable; public class DrawableCompat { public static android.graphics.drawable.Drawable wrap(android.graphics.drawable.Drawable d) { return d; } public static void setTint(android.graphics.drawable.Drawable d, int color) { d.tint=color; } }")
        addStub("package android.graphics; public class Typeface { public static final int BOLD=1; public int style; public static Typeface create(Typeface t,int s) { t.style=s; return t; } }")
        addStub("package androidx.core.content.res; public class ResourcesCompat { public static android.graphics.Typeface getFont(android.content.Context c,int id) { return new android.graphics.Typeface(); } }")
        addStub("package android.view.animation; public class Animation {}")
        addStub("package android.view.animation; public class AnimationUtils { public static Animation loadAnimation(android.content.Context c, int id) { return new Animation(); } }")
        addStub("package android.util; public class TypedValue { public float getFloat() { return 2f; } }")
        addStub("package android.os; public class Build { public static class VERSION { public static int SDK_INT=35; } }")
        addStub("package android.content.res; public class TypedArray {}")
        addStub("package android.content.res; public class ColorStateList {}")
        addStub("""package android.content.res; public class Resources {
            public String getString(int id) { return "text"; } public boolean getBoolean(int id) { return true; } public int getInteger(int id) { return 7; }
            public float getDimension(int id) { return 8f; } public int getDimensionPixelSize(int id) { return 8; }
            public String[] getStringArray(int id) { return new String[] {"x"}; } public int[] getIntArray(int id) { return new int[] {1}; }
            public CharSequence[] getTextArray(int id) { return new CharSequence[] {"x"}; } public TypedArray obtainTypedArray(int id) { return new TypedArray(); }
            public int getColor(int id) { return 42; } public ColorStateList getColorStateList(int id) { return new ColorStateList(); }
            public android.graphics.drawable.Drawable getDrawable(int id) { return new android.graphics.drawable.Drawable(); }
            public void getValue(int id, android.util.TypedValue v, boolean resolve) {}
        }""")
        addStub("package android.content; public class Context { public android.content.res.Resources getResources() { return new android.content.res.Resources(); } public int getColor(int id) { return getResources().getColor(id); } public android.content.res.ColorStateList getColorStateList(int id) { return getResources().getColorStateList(id); } public android.graphics.drawable.Drawable getDrawable(int id) { return getResources().getDrawable(id); } }")
        addStub("package javax.annotation; public @interface Nullable {}")
        addStub("package butterknife; public @interface BindView { int value(); }")
        addStub("package butterknife; public @interface BindViews { int[] value(); }")
        listOf("BindString", "BindBool", "BindInt", "BindArray", "BindDimen", "BindFloat", "BindColor", "BindBitmap", "BindAnim").forEach { addStub("package butterknife; public @interface $it { int value(); }") }
        addStub("package butterknife; public @interface BindFont { int value(); int style() default 0; }")
        addStub("package butterknife; public @interface BindDrawable { int value(); int tint() default 0; }")
        addStub("package butterknife; public @interface Optional {}")
        addStub("package butterknife; public @interface BindUnknown { int value(); }")
        listOf("OnClick", "OnLongClick", "OnTouch", "OnFocusChange", "OnCheckedChanged", "OnEditorAction", "OnItemClick", "OnItemLongClick").forEach {
            addStub("package butterknife; public @interface $it { int[] value() default {-1}; }")
        }
        mapOf("OnTextChanged" to listOf("TEXT_CHANGED", "BEFORE_TEXT_CHANGED", "AFTER_TEXT_CHANGED"), "OnItemSelected" to listOf("ITEM_SELECTED", "NOTHING_SELECTED"), "OnPageChange" to listOf("PAGE_SELECTED", "PAGE_SCROLLED", "PAGE_SCROLL_STATE_CHANGED")).forEach { (name, values) ->
            addStub("package butterknife; public @interface $name { int[] value() default {-1}; Callback callback() default Callback.${values.first()}; public enum Callback { ${values.joinToString()} } }")
        }
        addStub("package butterknife; public interface Unbinder { void unbind(); Unbinder EMPTY = new Unbinder() { public void unbind() {} }; }")
        addStub("package butterknife; public class ButterKnife { public static Unbinder bind(android.app.Activity target) { return null; } public static Unbinder bind(android.view.View target) { return null; } public static Unbinder bind(android.app.Dialog target) { return null; } public static Unbinder bind(Object target, android.view.View root) { return null; } }")
        addStub("package sample; public class R { public static class id { public static final int title=1, submit=2, other=3, root=4, divide_ask=6; } public static class string { public static final int label=5; } public static class layout { public static final int activity_fcm_settings=7; } }")
        addStub("package sample; public class R2 { public static class id { public static final int title=1, submit=2, other=3, root=4; } }")
    }
    private fun file(body: String, parent: String = "extends android.app.Activity") = myFixture.configureByText("Screen.java", "package sample; import butterknife.*; public class Screen $parent { $body }") as PsiJavaFile
    private fun plan(body: String, parent: String = "extends android.app.Activity") = ButterKnifeMigration.plan(file(body, parent))
    private fun valid(result: ButterKnifeMigration.Result, runner: String? = null): String {
        assertNull(result.reason)
        val after = result.after!!
        assertFalse(after.contains("butterknife"))
        assertNull(PsiTreeUtil.findChildOfType(myFixture.configureByText("Screen.java", after), PsiErrorElement::class.java))
        compileAndRun(mapOf("sample/Screen.java" to after), runner)
        return after
    }
    private fun compileAndRun(modified: Map<String, String>, runner: String? = null) {
        val folder = TemporaryFolder(); folder.create()
        try {
            val javaHome = System.getenv("JAVA_HOME") ?: error("JAVA_HOME must point to a full JDK")
            val suffix = if (System.getProperty("os.name").startsWith("Windows")) ".exe" else ""
            val extra = if (runner == null) emptyMap() else mapOf("sample/Runner.java" to "package sample; public class Runner { public static void main(String[] args) { $runner } }")
            val sources = (stubs.filterKeys { !it.startsWith("butterknife/") && it != "sample/R2.java" } + modified + extra).map { (path, text) ->
                val target = folder.root.toPath().resolve(path)
                java.nio.file.Files.createDirectories(target.parent); java.nio.file.Files.writeString(target, text); target.toString()
            }
            fun command(args: List<String>) {
                val process = ProcessBuilder(args).redirectErrorStream(true).start()
                val output = process.inputStream.bufferedReader().use { it.readText() }
                assertEquals(output, 0, process.waitFor())
            }
            command(listOf(java.nio.file.Path.of(javaHome, "bin", "javac$suffix").toString(), "-proc:none", "-d", folder.root.path) + sources)
            if (runner != null) command(listOf(java.nio.file.Path.of(javaHome, "bin", "java$suffix").toString(), "-cp", folder.root.path, "sample.Runner"))
        } finally { folder.delete() }
    }
    fun testActivityBindingAndRequiredFailure() {
        valid(plan("@BindView(R.id.title) android.widget.TextView title; void create() { ButterKnife.bind(this); }"), "Screen s=new Screen(); try { s.create(); throw new AssertionError(); } catch (IllegalStateException expected) {} android.widget.TextView v=new android.widget.TextView(); s.window.decor.children.put(R.id.title,v); s.create(); if(s.title!=v) throw new AssertionError();")
    }
    fun testFragmentNullableFieldAndNullGuardedUnbinder() {
        valid(plan("@javax.annotation.Nullable @BindView(R.id.title) android.widget.TextView title; Unbinder binder; void create(android.view.View v) { binder=ButterKnife.bind(this,v); } void destroy() { if(binder!=null) { binder.unbind(); binder=null; } }", ""), "Screen s=new Screen(); s.create(new android.view.View()); if(s.title!=null) throw new AssertionError(); s.destroy(); if(s.binder!=null) throw new AssertionError(); s.destroy();")
    }
    fun testClickDelegationAndUnbinding() {
        valid(plan("Unbinder binder; int count; @OnClick({R.id.submit,R.id.other}) void clicked(android.view.View v) { count++; } void create(android.view.View root) { binder=ButterKnife.bind(this,root); } void destroy() { binder.unbind(); }", ""), "Screen s=new Screen(); android.view.View r=new android.view.View(), a=new android.view.View(), b=new android.view.View(); r.children.put(R.id.submit,a); r.children.put(R.id.other,b); s.create(r); a.click.onClick(a); b.click.onClick(b); if(s.count!=2) throw new AssertionError(); s.destroy(); if(a.click!=null||b.click!=null) throw new AssertionError();")
    }
    fun testOptionalEventAndLongClickReturn() {
        valid(plan("@Optional @OnClick(R.id.other) void absent() {} @OnLongClick(R.id.submit) boolean longClick() { return false; } void create(android.view.View root) { ButterKnife.bind(this,root); }", ""), "Screen s=new Screen(); android.view.View r=new android.view.View(), b=new android.view.View(); r.children.put(R.id.submit,b); s.create(r); if(b.longClick.onLongClick(b)) throw new AssertionError();")
    }
    fun testNestedViewHolderAndMultipleClasses() {
        valid(plan("@BindView(R.id.title) android.view.View title; void create() { ButterKnife.bind(this); } static class Holder { @BindView(R.id.submit) android.view.View button; Holder(android.view.View v) { ButterKnife.bind(this,v); } }"), "android.view.View r=new android.view.View(), b=new android.view.View(); r.children.put(R.id.submit,b); Screen.Holder h=new Screen.Holder(r); if(h.button!=b) throw new AssertionError();")
    }
    fun testLocalUnbinderMultipleBindCallsAndEmpty() {
        valid(plan("@BindView(R.id.title) android.view.View title; void create(android.view.View a, android.view.View b) { Unbinder first=ButterKnife.bind(this,a); Unbinder second=ButterKnife.bind(this,b); first.unbind(); second.unbind(); Unbinder empty=Unbinder.EMPTY; empty.unbind(); empty.unbind(); }", ""))
    }
    fun testR2AndStaticImportedId() {
        val psi = myFixture.configureByText("Screen.java", "package sample; import butterknife.*; import static sample.R2.id.root; public class Screen extends android.app.Activity { @OnClick(root) void clicked() {} void create() { ButterKnife.bind(this); } }") as PsiJavaFile
        val after=valid(ButterKnifeMigration.plan(psi)); assertTrue(after.contains("root.findViewById(sample.R.id.root)"))
    }
    fun testCommonResourceAnnotations() {
        valid(plan("@BindString(R.string.label) String text; @BindBool(R.string.label) boolean bool; @BindInt(R.string.label) int number; @BindDimen(R.string.label) int size; @BindDimen(R.string.label) float dimen; @BindFloat(R.string.label) float value; @BindArray(R.string.label) java.lang.String[] array; @BindArray(R.string.label) int[] ints; @BindArray(R.string.label) java.lang.CharSequence[] texts; @BindArray(R.string.label) android.content.res.TypedArray typed; @BindColor(R.string.label) int color; @BindColor(R.string.label) android.content.res.ColorStateList colors; @BindDrawable(R.string.label) android.graphics.drawable.Drawable drawable; @BindBitmap(R.string.label) android.graphics.Bitmap bitmap; @BindAnim(R.string.label) android.view.animation.Animation anim; void create() { ButterKnife.bind(this); }"), "Screen s=new Screen(); s.create(); if(!s.text.equals(\"text\")||!s.bool||s.number!=7||s.size!=8||s.value!=2f||s.color!=42) throw new AssertionError();")
    }
    fun testBindViewsArrayAndList() {
        valid(plan("@BindViews({R.id.title,R.id.submit}) android.view.View[] array; @javax.annotation.Nullable @BindViews({R.id.title,R.id.other}) java.util.List<android.view.View> list; void create(android.view.View v) { ButterKnife.bind(this,v); }", ""), "Screen s=new Screen(); android.view.View r=new android.view.View(); r.children.put(R.id.title,new android.view.View()); r.children.put(R.id.submit,new android.view.View()); s.create(r); if(s.array.length!=2||s.list.size()!=1) throw new AssertionError();")
    }
    fun testTextCallbacksShareListenerAndRemoveIt() {
        valid(plan("Unbinder binder; int count; @OnTextChanged(value=R.id.title,callback=OnTextChanged.Callback.BEFORE_TEXT_CHANGED) void before() { count++; } @OnTextChanged(R.id.title) void changed(java.lang.CharSequence s, int start) { count+=start; } @OnTextChanged(value=R.id.title,callback=OnTextChanged.Callback.AFTER_TEXT_CHANGED) void after() { count++; } void create(android.view.View v) { binder=ButterKnife.bind(this,v); } void destroy() { binder.unbind(); }", ""), "Screen s=new Screen(); android.view.View r=new android.view.View(); android.widget.TextView t=new android.widget.TextView(); r.children.put(R.id.title,t); s.create(r); if(t.watchers.size()!=1) throw new AssertionError(); android.text.TextWatcher w=t.watchers.get(0); w.beforeTextChanged(\"x\",0,0,0); w.onTextChanged(\"x\",3,0,1); w.afterTextChanged(null); if(s.count!=5) throw new AssertionError(); s.destroy(); if(!t.watchers.isEmpty()) throw new AssertionError();")
    }
    fun testFocusTouchCheckedAndEditorCallbacks() {
        valid(plan("@OnFocusChange(R.id.title) void focus(boolean focused) {} @OnTouch(R.id.title) boolean touch(android.view.MotionEvent e) { return false; } @OnCheckedChanged(R.id.submit) void checked(boolean yes) {} @OnEditorAction(R.id.title) boolean editor(int action, android.view.KeyEvent event) { return true; } void create(android.view.View v) { ButterKnife.bind(this,v); }", ""))
    }
    fun testAdapterCallbacks() {
        valid(plan("@OnItemClick(R.id.title) void click(int p, long id) {} @OnItemLongClick(R.id.title) boolean longClick(long id) { return true; } @OnItemSelected(R.id.title) void selected(int p) {} @OnItemSelected(value=R.id.title,callback=OnItemSelected.Callback.NOTHING_SELECTED) void nothing() {} void create(android.view.View v) { ButterKnife.bind(this,v); }", ""))
    }
    fun testPageCallbacksAndCleanup() {
        valid(plan("Unbinder binder; @OnPageChange(R.id.title) void selected(int p) {} @OnPageChange(value=R.id.title,callback=OnPageChange.Callback.PAGE_SCROLLED) void scrolled(int p,float offset,int pixels) {} @OnPageChange(value=R.id.title,callback=OnPageChange.Callback.PAGE_SCROLL_STATE_CHANGED) void state(int state) {} void create(android.view.View v) { binder=ButterKnife.bind(this,v); } void destroy() { binder.unbind(); }", ""), "Screen s=new Screen(); android.view.View r=new android.view.View(); androidx.viewpager.widget.ViewPager p=new androidx.viewpager.widget.ViewPager(); r.children.put(R.id.title,p); s.create(r); if(p.listeners.size()!=1) throw new AssertionError(); s.destroy(); if(!p.listeners.isEmpty()) throw new AssertionError();")
    }
    fun testInheritedBindingWithoutChildBindCall() {
        val parent=addStub("package sample; import butterknife.*; public class Parent extends android.app.Activity { @BindView(R.id.title) public android.view.View title; public Unbinder binder; public void create() { binder=ButterKnife.bind(this); } public void destroy() { binder.unbind(); } }")
        val child=file("@BindView(R.id.submit) android.view.View button;", "extends Parent")
        val results=ButterKnifeMigration.planBatch(listOf(parent,child))
        results.values.forEach { assertNull(it.reason); assertNotNull(it.after) }
        compileAndRun(mapOf("sample/Parent.java" to results.getValue(parent).after!!,"sample/Screen.java" to results.getValue(child).after!!), "Screen s=new Screen(); android.view.View a=new android.view.View(), b=new android.view.View(); s.window.decor.children.put(R.id.title,a); s.window.decor.children.put(R.id.submit,b); s.create(); if(s.title!=a||s.button!=b) throw new AssertionError(); s.destroy(); if(s.title!=null||s.button!=null) throw new AssertionError();")
    }
    fun testBaseClassBindWithOnlyChildAnnotations() {
        val parent=addStub("package sample; import butterknife.*; public class Parent extends android.app.Activity { public void create() { ButterKnife.bind(this); } }")
        val child=file("@BindView(R.id.title) android.view.View title;", "extends Parent")
        val results=ButterKnifeMigration.planBatch(listOf(parent,child))
        results.values.forEach { assertNull(it.reason) }
        compileAndRun(mapOf("sample/Parent.java" to results.getValue(parent).after!!,"sample/Screen.java" to results.getValue(child).after!!), "Screen s=new Screen(); android.view.View v=new android.view.View(); s.window.decor.children.put(R.id.title,v); s.create(); if(s.title!=v) throw new AssertionError();")
    }
    fun testExternalBindingOfHolder() {
        valid(plan("static class Holder { @BindView(R.id.title) android.view.View title; } void create(Holder holder, android.view.View root) { ButterKnife.bind(holder,root); }"), "Screen s=new Screen(); Screen.Holder h=new Screen.Holder(); android.view.View r=new android.view.View(), v=new android.view.View(); r.children.put(R.id.title,v); s.create(h,r); if(h.title!=v) throw new AssertionError();")
    }
    fun testFailedChildKeepsParentUnchanged() {
        val parent=addStub("package sample; import butterknife.*; public class Parent extends android.app.Activity { @BindView(R.id.title) public android.view.View title; public void create() { ButterKnife.bind(this); } }")
        val child=file("@BindUnknown(R.id.submit) android.view.View button;", "extends Parent")
        val results=ButterKnifeMigration.planBatch(listOf(parent,child))
        assertNull(results.getValue(parent).after); assertNull(results.getValue(child).after)
        assertTrue(results.getValue(parent).reason!!.contains("子类"))
        assertTrue(results.getValue(child).reason!!.contains("BindUnknown"))
    }
    fun testMissingAncestorSkipsWithUsefulReason() {
        addStub("package sample; public class Parent extends android.app.Activity { @butterknife.BindView(R.id.title) android.view.View title; }")
        val result=plan("@BindView(R.id.submit) android.view.View button; void create() { ButterKnife.bind(this); }", "extends Parent")
        assertNull(result.after); assertTrue(result.reason!!.contains("父类"))
    }
    fun testStaticUnbinderSkips() {
        val result=plan("@BindView(R.id.title) android.view.View title; static Unbinder binder; void create() { binder=ButterKnife.bind(this); }")
        assertNull(result.after); assertTrue(result.reason!!.contains("静态"))
    }
    fun testUnbinderApiParameterSkips() {
        val result=plan("@BindView(R.id.title) android.view.View title; void create() { ButterKnife.bind(this); } void external(Unbinder value) {}")
        assertNull(result.after); assertTrue(result.reason!!.contains("参数"))
    }
    fun testUnsupportedAnnotationNamesAreReported() {
        val result=plan("@BindUnknown(R.id.title) android.view.View title; void create() { ButterKnife.bind(this); }")
        assertNull(result.after); assertTrue(result.reason!!.contains("@BindUnknown"))
    }
    fun testFontAndDrawableTint() {
        valid(plan("@BindFont(value=R.string.label,style=android.graphics.Typeface.BOLD) android.graphics.Typeface font; @BindDrawable(value=R.string.label,tint=R.string.label) android.graphics.drawable.Drawable icon; void create() { ButterKnife.bind(this); }"), "Screen s=new Screen(); s.create(); if(s.font.style!=1||s.icon.tint!=42) throw new AssertionError();")
    }
    fun testNoBindingEntryDoesNotRemoveAnnotations() {
        val result=plan("@BindView(R.id.title) android.view.View title;")
        assertNull(result.after); assertTrue(result.reason!!.contains("绑定入口"))
    }
    fun testPassingUnbinderToAnExternalApiSkips() {
        addStub("package sample; public class Api { public static void consume(butterknife.Unbinder binder) {} }")
        val result=plan("Unbinder binder; @BindView(R.id.title) android.view.View title; void create() { binder=ButterKnife.bind(this); Api.consume(binder); }")
        assertNull(result.after); assertTrue(result.reason!!.contains("传递"))
    }
    fun testSingleArgumentTargetIsEvaluatedOnce() {
        valid(plan("int count; @BindView(R.id.title) android.view.View title; Screen getScreen() { count++; return this; } void create() { ButterKnife.bind(getScreen()); }"), "Screen s=new Screen(); s.window.decor.children.put(R.id.title,new android.view.View()); s.create(); if(s.count!=1) throw new AssertionError();")
    }
    fun testUnbinderReleasesCleanupClosuresAndDetectsDoubleUnbind() {
        valid(plan("Unbinder binder; @BindView(R.id.title) android.view.View title; void create() { binder=ButterKnife.bind(this); } void destroy() { binder.unbind(); }"), "Screen s=new Screen(); s.window.decor.children.put(R.id.title,new android.view.View()); s.create(); s.destroy(); if(s.title!=null) throw new AssertionError(); try { java.lang.reflect.Field f=s.binder.getClass().getDeclaredField(\"cleanups\"); f.setAccessible(true); if(f.get(s.binder)!=null) throw new AssertionError(); } catch(ReflectiveOperationException e) { throw new AssertionError(e); } try { s.destroy(); throw new AssertionError(); } catch(IllegalStateException expected) {}")
    }
    fun testAdapterRowViewDoesNotMatchParentAdapter() {
        valid(plan("android.view.View received; @OnItemClick(R.id.title) void clicked(android.view.View view) { received=view; } void create(android.view.View root) { ButterKnife.bind(this,root); }", ""), "Screen s=new Screen(); android.view.View root=new android.view.View(), row=new android.view.View(); android.widget.AdapterView<Object> adapter=new android.widget.AdapterView<Object>(); root.children.put(R.id.title,adapter); s.create(root); adapter.item.onItemClick(adapter,row,4,7L); if(s.received!=row) throw new AssertionError();")
    }
    fun testNullableViewArrayFiltersMissingViews() {
        valid(plan("@javax.annotation.Nullable @BindViews({R.id.title,R.id.other}) android.view.View[] views; void create(android.view.View root) { ButterKnife.bind(this,root); }", ""), "Screen s=new Screen(); android.view.View root=new android.view.View(), v=new android.view.View(); root.children.put(R.id.title,v); s.create(root); if(s.views.length!=1||s.views[0]!=v) throw new AssertionError();")
    }
    fun testMultipleClickMethodsOnSameView() {
        valid(plan("int count; @OnClick(R.id.title) void first() { count++; } @OnClick(R.id.title) void second() { count+=2; } void create(android.view.View root) { ButterKnife.bind(this,root); }", ""), "Screen s=new Screen(); android.view.View root=new android.view.View(), v=new android.view.View(); root.children.put(R.id.title,v); s.create(root); v.click.onClick(v); if(s.count!=3) throw new AssertionError();")
    }
    fun testUnusedButterKnifeImportsAreRemoved() {
        valid(plan("void empty() {}"))
    }
    fun testR2BusinessReferencesSkipWithoutDeletingCode() {
        val result=plan("@BindView(R2.id.title) android.view.View title; int id=R2.id.title; void create() { ButterKnife.bind(this); }")
        assertNull(result.after); assertTrue(result.reason!!.contains("业务代码"))
    }
    fun testLegacySupportFontTintAndViewPager() {
        val moved = mapOf(
            "androidx/core/content/res/ResourcesCompat.java" to ("androidx.core.content.res.ResourcesCompat" to "android.support.v4.content.res"),
            "androidx/core/graphics/drawable/DrawableCompat.java" to ("androidx.core.graphics.drawable.DrawableCompat" to "android.support.v4.graphics.drawable"),
            "androidx/viewpager/widget/ViewPager.java" to ("androidx.viewpager.widget.ViewPager" to "android.support.v4.view"))
        moved.forEach { (path, names) ->
            val original = stubs.remove(path)!!
            com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) {
                com.intellij.psi.JavaPsiFacade.getInstance(project).findClass(names.first, com.intellij.psi.search.GlobalSearchScope.projectScope(project))!!.containingFile.delete()
            }
            addStub(original.replace(original.substringAfter("package ").substringBefore(';'), names.second))
        }
        valid(plan("@BindFont(R.string.label) android.graphics.Typeface font; @BindDrawable(value=R.string.label,tint=R.string.label) android.graphics.drawable.Drawable icon; @OnPageChange(R.id.title) void page(int p) {} void create(android.view.View v) { ButterKnife.bind(this,v); }", ""), "Screen s=new Screen(); android.view.View root=new android.view.View(); root.children.put(R.id.title,new android.support.v4.view.ViewPager()); s.create(root); if(s.font==null||s.icon.tint!=42) throw new AssertionError();")
    }
    fun testSingleFileScopeExcludesSiblingAndDirectoryScopeIncludesBoth() {
        val selected = myFixture.addFileToProject("scope/Selected.java", "class Selected {}")
        val sibling = myFixture.addFileToProject("scope/Sibling.java", "class Sibling {}")
        val action = com.github.kevin58cmd.customkotlinxandroidsynthetic.actions.MigrateButterKnifeAction
        assertEquals(listOf(selected.virtualFile), action.collectMigrationFiles(project, selected.virtualFile))
        assertEquals(setOf(selected.virtualFile, sibling.virtualFile), action.collectMigrationFiles(project, selected.virtualFile.parent).toSet())
        val kotlin = myFixture.addFileToProject("scope/Other.kt", "class Other")
        assertEquals(listOf(kotlin.virtualFile), action.collectMigrationFiles(project, kotlin.virtualFile))
    }

    fun testSingleParentFileSkipsWhenChildRequiresMigration() {
        val parent = addStub("package sample; public class Parent extends android.app.Activity { @butterknife.BindView(R.id.title) android.view.View title; void create() { butterknife.ButterKnife.bind(this); } }")
        val child = addStub("package sample; public class Child extends Parent { @butterknife.BindView(R.id.submit) android.view.View button; }")
        val beforeParent = parent.text
        val beforeChild = child.text
        val result = ButterKnifeMigration.plan(parent)
        assertNull(result.after)
        assertTrue(result.reason!!.contains("子类"))
        assertEquals(beforeParent, parent.text)
        assertEquals(beforeChild, child.text)
    }
    fun testSelectedChildExpandsToBaseAndSiblingAndRunsInheritedBinding() {
        val parent = addStub("package sample; public class Parent extends android.app.Activity { public butterknife.Unbinder binder; public void create() { binder=butterknife.ButterKnife.bind(this); } public void destroy() { binder.unbind(); } }")
        val child = addStub("package sample; public class Child extends Parent { @butterknife.BindView(R.id.title) android.view.View title; }")
        val sibling = addStub("package sample; public class Sibling extends Parent { @butterknife.BindView(R.id.submit) android.view.View button; }")
        val unrelated = myFixture.addFileToProject("other/Unrelated.java", "package other; public class Unrelated extends android.app.Activity { @butterknife.BindView(sample.R.id.title) android.view.View title; void create() { butterknife.ButterKnife.bind(this); } }") as PsiJavaFile
        val before = listOf(parent, child, sibling, unrelated).associateWith { it.text }
        // Sibling precedes parent to exercise the transitive closure.
        val scope = ButterKnifeMigration.relatedFiles(child, listOf(sibling, unrelated, parent, child))
        assertEquals(setOf(child, parent, sibling), scope.toSet())
        val results = ButterKnifeMigration.planBatch(scope)
        results.values.forEach { assertNull(it.reason); assertNotNull(it.after) }
        before.forEach { (file, text) -> assertEquals(text, file.text) }
        compileAndRun(results.mapKeys { "sample/" + it.key.name }.mapValues { it.value.after!! }, "Child c=new Child(); Sibling s=new Sibling(); android.view.View a=new android.view.View(), b=new android.view.View(); c.window.decor.children.put(R.id.title,a); s.window.decor.children.put(R.id.submit,b); c.create(); s.create(); if(c.title!=a||s.button!=b) throw new AssertionError(); c.destroy(); s.destroy(); if(c.title!=null||s.button!=null) throw new AssertionError();")
    }

    fun testRelatedScopeIncludesExternalCallerAndExcludesIndependentFile() {
        val holder = addStub("package sample; public class Holder { @butterknife.BindView(R.id.title) android.view.View title; }")
        val caller = addStub("package sample; public class Caller { public static void bind(Holder h, android.view.View root) { butterknife.ButterKnife.bind(h,root); } }")
        val unrelated = myFixture.addFileToProject("other/Other.java", "package other; public class Other {}") as PsiJavaFile
        val scope = ButterKnifeMigration.relatedFiles(holder, listOf(caller, holder, unrelated))
        assertEquals(setOf(holder, caller), scope.toSet())
        val results = ButterKnifeMigration.planBatch(scope)
        results.values.forEach { assertNull(it.reason); assertNotNull(it.after) }
        compileAndRun(results.mapKeys { "sample/" + it.key.name }.mapValues { it.value.after!! }, "Holder h=new Holder(); android.view.View root=new android.view.View(), v=new android.view.View(); root.children.put(R.id.title,v); Caller.bind(h,root); if(h.title!=v) throw new AssertionError();")
        assertEquals(listOf(unrelated), ButterKnifeMigration.relatedFiles(unrelated, listOf(holder,caller,unrelated)))
    }

    fun testExpandedUnsupportedSiblingPreventsPartialInheritanceMigration() {
        val parent = addStub("package sample; public class Parent extends android.app.Activity { void create() { butterknife.ButterKnife.bind(this); } }")
        val child = addStub("package sample; public class Child extends Parent { @butterknife.BindView(R.id.title) android.view.View title; }")
        val sibling = addStub("package sample; public class Sibling extends Parent { @butterknife.BindUnknown(R.id.submit) android.view.View button; }")
        val scope = ButterKnifeMigration.relatedFiles(child, listOf(child,parent,sibling))
        assertEquals(3, scope.size)
        val results = ButterKnifeMigration.planBatch(scope)
        assertTrue(results.values.all { it.after == null })
        assertTrue(results.getValue(sibling).reason!!.contains("BindUnknown"))
    }
    private var kotlinLibraryAdded = false
    private fun kotlinCompilerHome(): String {
        System.getenv("KOTLIN_COMPILER_HOME")?.let { return it }
        // IDE plugin class loaders may not populate protectionDomain.codeSource.
        val plugin = com.intellij.ide.plugins.PluginManagerCore.getPlugin(com.intellij.openapi.extensions.PluginId.getId("org.jetbrains.kotlin"))
        plugin?.pluginPath?.resolve("kotlinc")?.toFile()?.let {
            if (java.io.File(it, "lib/kotlin-compiler.jar").isFile) return it.path
        }
        val bundled = java.io.File(com.intellij.openapi.application.PathManager.getHomePath(), "plugins/Kotlin/kotlinc")
        if (java.io.File(bundled, "lib/kotlin-compiler.jar").isFile) return bundled.path
        val codeSource = org.jetbrains.kotlin.psi.KtFile::class.java.protectionDomain.codeSource
            ?: error("Kotlin compiler not found: plugin=${plugin?.pluginPath}, IDE=${com.intellij.openapi.application.PathManager.getHomePath()}; set KOTLIN_COMPILER_HOME")
        val location = java.io.File(codeSource.location.toURI())
        return generateSequence(location.parentFile) { it.parentFile }.take(4)
            .map { java.io.File(it, "kotlinc") }.first { java.io.File(it, "lib/kotlin-compiler.jar").isFile }.path
    }
    private fun kotlinFile(name: String, source: String): org.jetbrains.kotlin.psi.KtFile {
        if (!kotlinLibraryAdded) {
            com.intellij.testFramework.PsiTestUtil.addLibrary(module, "kotlin-stdlib", java.io.File(kotlinCompilerHome(),"lib").path, "kotlin-stdlib.jar")
            kotlinLibraryAdded = true
        }
        return myFixture.addFileToProject("sample/$name.kt", source) as org.jetbrains.kotlin.psi.KtFile
    }

    private fun mixedPlan(vararg files: com.intellij.psi.PsiFile): Map<com.intellij.psi.PsiFile, ButterKnifeMigration.Result> =
        com.github.kevin58cmd.customkotlinxandroidsynthetic.migration.MixedButterKnifeMigration.planBatch(files.toList())

    private fun compileMixed(results: Map<com.intellij.psi.PsiFile, ButterKnifeMigration.Result>, runner: String) {
        results.values.forEach { assertNull(it.reason); assertNotNull(it.after); assertFalse(it.after!!.contains("butterknife")) }
        val folder = TemporaryFolder(); folder.create()
        try {
            val javaHome = System.getenv("JAVA_HOME") ?: error("JAVA_HOME must point to a JDK")
            val kotlinHome = kotlinCompilerHome()
            val sources = stubs.filterKeys { !it.startsWith("butterknife/") && it != "sample/R2.java" }.toMutableMap()
            results.forEach { (file,result) -> sources["sample/${file.name}"] = result.after!! }
            sources["sample/Runner.java"] = "package sample; public class Runner { public static void main(String[] args) { $runner } }"
            val paths = sources.map { (path,text) ->
                val target = folder.root.toPath().resolve(path)
                java.nio.file.Files.createDirectories(target.parent); java.nio.file.Files.writeString(target,text); target.toString()
            }
            fun command(args: List<String>) {
                val process = ProcessBuilder(args).redirectErrorStream(true).start()
                val output = process.inputStream.bufferedReader().use { it.readText() }
                assertEquals(output, 0, process.waitFor())
            }
            val compilerJars = java.io.File(kotlinHome,"lib").listFiles()!!.filter { it.extension == "jar" }.joinToString(java.io.File.pathSeparator) { it.path }
            val stdlib = java.io.File(kotlinHome,"lib/kotlin-stdlib.jar").path
            val classpath = folder.root.path + java.io.File.pathSeparator + stdlib
            command(listOf("$javaHome/bin/java.exe", "-Dkotlin.home=$kotlinHome", "-cp", compilerJars, "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-jvm-target", "17", "-d", folder.root.path) + paths)
            command(listOf("$javaHome/bin/javac.exe", "-proc:none", "-cp", classpath, "-d", folder.root.path) + paths.filter { it.endsWith(".java") })
            command(listOf("$javaHome/bin/java.exe", "-cp", classpath, "sample.Runner"))
        } finally { folder.delete() }
    }

    fun testKotlinBaseWithJavaChildAndSiblingBindsAndCleansUp() {
        val parent = kotlinFile("KotlinBase", """package sample
            import butterknife.ButterKnife
            import butterknife.Unbinder
            open class KotlinBase : android.app.Activity() {
                private var binder: Unbinder? = null
                fun create() { binder = ButterKnife.bind(this) }
                fun destroy() { binder?.unbind(); binder = null }
            }
        """)
        val child = addStub("package sample; public class JavaChild extends KotlinBase { @butterknife.BindView(R.id.title) public android.view.View title; }")
        val sibling = kotlinFile("KotlinChild", """package sample
            import butterknife.BindView
            class KotlinChild : KotlinBase() { @field:BindView(R.id.submit) lateinit var button: android.view.View }
        """)
        val scope = com.github.kevin58cmd.customkotlinxandroidsynthetic.migration.MixedButterKnifeMigration.relatedFiles(child,listOf(child,parent,sibling))
        assertEquals(setOf(child,parent,sibling), scope.toSet())
        compileMixed(mixedPlan(*scope.toTypedArray()), "JavaChild a=new JavaChild(); KotlinChild b=new KotlinChild(); android.view.View v=new android.view.View(), w=new android.view.View(); a.window.decor.children.put(R.id.title,v); b.window.decor.children.put(R.id.submit,w); a.create(); b.create(); if(a.title!=v||b.getButton()!=w) throw new AssertionError(); a.destroy(); b.destroy(); if(a.title!=null) throw new AssertionError(); try { b.getButton(); throw new AssertionError(); } catch(kotlin.UninitializedPropertyAccessException expected) {}")
    }

    fun testJavaBaseWithKotlinChildBindsAndCleansUp() {
        val parent = addStub("package sample; public class JavaBase extends android.app.Activity { public butterknife.Unbinder binder; public void create() { binder=butterknife.ButterKnife.bind(this); } public void destroy() { binder.unbind(); } }")
        val child = kotlinFile("KotlinChild", """package sample
            import butterknife.BindView
            class KotlinChild : JavaBase() { @field:BindView(R.id.title) var title: android.view.View? = null }
        """)
        compileMixed(mixedPlan(parent,child), "KotlinChild c=new KotlinChild(); android.view.View v=new android.view.View(); c.window.decor.children.put(R.id.title,v); c.create(); if(c.getTitle()!=v) throw new AssertionError(); c.destroy(); if(c.getTitle()!=null) throw new AssertionError();")
    }

    fun testKotlinViewBindingClickAndCleanup() {
        val source = kotlinFile("KotlinScreen", """package sample
            import butterknife.*
            class KotlinScreen {
                @field:BindView(R.id.title) lateinit var title: android.widget.TextView
                private var binder: Unbinder? = null
                var count: Int = 0
                @OnClick(R.id.title) fun click() { count++ }
                fun create(root: android.view.View) { binder = ButterKnife.bind(this, root) }
                fun destroy() { binder?.unbind(); binder = null }
            }
        """)
        compileMixed(mixedPlan(source), "KotlinScreen s=new KotlinScreen(); android.view.View root=new android.view.View(); android.widget.TextView v=new android.widget.TextView(); root.children.put(R.id.title,v); s.create(root); if(s.getTitle()!=v) throw new AssertionError(); v.click.onClick(v); if(s.getCount()!=1) throw new AssertionError(); s.destroy(); if(v.click!=null) throw new AssertionError();")
    }

    private fun fcmSettingsSource() = kotlinFile("FcmSettingsActivity", """package sample
        import android.os.Bundle
        import android.view.View
        import butterknife.BindView
        import butterknife.ButterKnife
        import butterknife.Unbinder
        class FcmSettingsActivity : android.app.Activity() {
            @JvmField
            @BindView(R.id.divide_ask)
            var divideAsk: View? = null
            private lateinit var mOldStates: BooleanArray
            private var unbinder: Unbinder? = null
            override fun onCreate(savedInstanceState: Bundle?) {
                super.onCreate(savedInstanceState)
                setContentView(R.layout.activity_fcm_settings)
                unbinder = ButterKnife.bind(this)
            }
            override fun onDestroy() { super.onDestroy(); unbinder?.unbind(); unbinder = null }
        }
    """)

    private fun verifyFcmSettings(source: org.jetbrains.kotlin.psi.KtFile) {
        val results = mixedPlan(source)
        val after = results.getValue(source).after
        assertNull(results.getValue(source).reason)
        assertFalse(after!!.contains("@JvmField"))
        assertTrue(after.contains("private lateinit var mOldStates: BooleanArray"))
        compileMixed(results, "FcmSettingsActivity s=new FcmSettingsActivity(); android.view.View v=new android.view.View(); s.window.decor.children.put(R.id.divide_ask,v); s.onCreate(null); if(s.getDivideAsk()!=v) throw new AssertionError(); s.onDestroy(); if(s.getDivideAsk()!=null) throw new AssertionError();")
    }

    fun testKotlinJvmFieldNullableViewInActivityOnCreate() {
        verifyFcmSettings(fcmSettingsSource())
    }

    fun testKotlinSwitchCompatJvmFieldBindingAndCleanup() {
        addStub("package androidx.appcompat.widget; public class SwitchCompat extends android.widget.CompoundButton {}")
        val source = kotlinFile("KotlinSwitchScreen", """package sample
            import androidx.appcompat.widget.SwitchCompat
            import butterknife.BindView
            import butterknife.ButterKnife
            import butterknife.Unbinder
            import kotlin.jvm.JvmField
            class KotlinSwitchScreen : android.app.Activity() {
                @JvmField
                @BindView(R.id.submit)
                var switchArticle: SwitchCompat? = null
                private var unbinder: Unbinder? = null
                fun create() { unbinder = ButterKnife.bind(this) }
                fun destroy() { unbinder?.unbind(); unbinder = null }
            }
        """)
        val results = mixedPlan(source)
        assertNull(results.getValue(source).reason)
        assertFalse(results.getValue(source).after!!.contains("@JvmField"))
        assertFalse(results.getValue(source).after!!.contains("import kotlin.jvm.JvmField"))
        compileMixed(results, "KotlinSwitchScreen s=new KotlinSwitchScreen(); androidx.appcompat.widget.SwitchCompat v=new androidx.appcompat.widget.SwitchCompat(); s.window.decor.children.put(R.id.submit,v); s.create(); if(s.getSwitchArticle()!=v) throw new AssertionError(); s.destroy(); if(s.getSwitchArticle()!=null) throw new AssertionError();")
    }

    fun testJavaSwitchCompatBindingAndCleanup() {
        addStub("package androidx.appcompat.widget; public class SwitchCompat extends android.widget.CompoundButton {}")
        valid(plan("@BindView(R.id.submit) androidx.appcompat.widget.SwitchCompat switchArticle; Unbinder unbinder; void create() { unbinder=ButterKnife.bind(this); } void destroy() { unbinder.unbind(); }"),
            "Screen s=new Screen(); androidx.appcompat.widget.SwitchCompat v=new androidx.appcompat.widget.SwitchCompat(); s.window.decor.children.put(R.id.submit,v); s.create(); if(s.switchArticle!=v) throw new AssertionError(); s.destroy(); if(s.switchArticle!=null) throw new AssertionError();")
    }

    fun testKotlinJvmFieldAliasesAndUnrelatedFields() {
        val source = kotlinFile("JvmFieldAliases", """package sample
            import butterknife.*
            import kotlin.jvm.JvmField as Exposed
            class JvmFieldAliases : android.app.Activity() {
                @Exposed @BindView(R.id.title) var title: android.view.View? = null
                @kotlin.jvm.JvmField @BindView(R.id.submit) var button: android.view.View? = null
                @Exposed var count: Int = 3
                fun create() { ButterKnife.bind(this) }
            }
        """)
        val results = mixedPlan(source)
        assertNull(results.getValue(source).reason)
        val after = results.getValue(source).after!!
        assertFalse(after.contains("@kotlin.jvm.JvmField"))
        assertEquals(1, Regex("@Exposed").findAll(after).count())
        assertTrue(after.contains("import kotlin.jvm.JvmField as Exposed"))
        compileMixed(results, "JvmFieldAliases s=new JvmFieldAliases(); android.view.View a=new android.view.View(), b=new android.view.View(); s.window.decor.children.put(R.id.title,a); s.window.decor.children.put(R.id.submit,b); s.create(); if(s.getTitle()!=a||s.getButton()!=b||s.count!=3) throw new AssertionError();")
    }

    fun testKotlinJvmFieldActivityWithUnresolvedButterKnifeClass() {
        val source = fcmSettingsSource()
        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) {
            myFixture.findFileInTempDir("butterknife/ButterKnife.java")!!.delete(this)
        }
        verifyFcmSettings(source)
    }

    fun testKotlinUnresolvedImportedBindDoesNotMatchShadowedMember() {
        val source = kotlinFile("Shadowed", """package sample
            import butterknife.BindView
            import butterknife.ButterKnife
            class Shadowed : android.app.Activity() {
                val ButterKnife: Any = Any()
                @JvmField @BindView(R.id.title) var title: android.view.View? = null
                fun create() { ButterKnife.bind(this) }
            }
        """)
        val result = mixedPlan(source).getValue(source)
        assertNull(result.after)
        assertTrue(result.reason!!.contains("绑定入口"))
    }

    fun testKotlinUnknownAnnotationBlocksMixedInheritance() {
        val parent = kotlinFile("KotlinBase", """package sample
            import butterknife.ButterKnife
            open class KotlinBase : android.app.Activity() { fun create() { ButterKnife.bind(this) } }
        """)
        val child = addStub("package sample; public class JavaChild extends KotlinBase { @butterknife.BindUnknown(R.id.title) public android.view.View title; }")
        val results = mixedPlan(parent,child)
        assertTrue(results.values.all { it.after == null })
        assertTrue(results.getValue(child).reason!!.contains("BindUnknown"))
    }
    fun testKotlinResourceBindings() {
        val source = kotlinFile("KotlinResources", """package sample
            import butterknife.*
            class KotlinResources : android.app.Activity() {
                @field:BindString(R.string.label) var text: String = ""
                @field:BindBool(R.string.label) var yes: Boolean = false
                @field:BindInt(R.string.label) var number: Int = 0
                @field:BindDimen(R.string.label) var size: Int = 0
                @field:BindFloat(R.string.label) var value: Float = 0f
                @field:BindArray(R.string.label) lateinit var strings: Array<String>
                @field:BindColor(R.string.label) var color: Int = 0
                @field:BindDrawable(R.string.label) lateinit var icon: android.graphics.drawable.Drawable
                @field:BindBitmap(R.string.label) lateinit var bitmap: android.graphics.Bitmap
                @field:BindAnim(R.string.label) lateinit var animation: android.view.animation.Animation
                @field:BindFont(R.string.label) lateinit var font: android.graphics.Typeface
                fun create() { ButterKnife.bind(this) }
            }
        """)
        compileMixed(mixedPlan(source), "KotlinResources s=new KotlinResources(); s.create(); if(!s.getText().equals(\"text\")||!s.getYes()||s.getNumber()!=7||s.getSize()!=8||s.getValue()!=2f||s.getColor()!=42||s.getStrings().length!=1||s.getIcon()==null||s.getFont()==null) throw new AssertionError();")
    }

    fun testKotlinCollectionsTextListenerAndNonNullUnbind() {
        val source = kotlinFile("KotlinCollections", """package sample
            import butterknife.*
            class KotlinCollections {
                @field:BindViews(value=[R.id.title,R.id.submit]) lateinit var views: Array<android.view.View>
                @field:BindView(R.id.other) var absent: android.view.View? = null
                var count: Int = 0
                private var binder: Unbinder? = null
                @OnTextChanged(R.id.title) fun changed(text: CharSequence, start: Int) { count += start }
                @OnLongClick(R.id.submit) fun longClick(): Boolean = false
                fun create(root: android.view.View) { binder = ButterKnife.bind(this, root) }
                fun destroy() { if (binder != null) { binder!!.unbind(); binder = null } }
            }
        """)
        compileMixed(mixedPlan(source), "KotlinCollections s=new KotlinCollections(); android.view.View root=new android.view.View(), button=new android.view.View(); android.widget.TextView text=new android.widget.TextView(); root.children.put(R.id.title,text); root.children.put(R.id.submit,button); s.create(root); if(s.getViews().length!=2||s.getAbsent()!=null) throw new AssertionError(); text.watchers.get(0).onTextChanged(\"x\",3,0,1); if(s.getCount()!=3||button.longClick.onLongClick(button)) throw new AssertionError(); s.destroy(); if(!text.watchers.isEmpty()||button.longClick!=null) throw new AssertionError();")
    }

    fun testKotlinAliasedImportsAndInferredUnbinder() {
        val source = kotlinFile("KotlinAlias", """package sample
            import butterknife.BindView as Bind
            import butterknife.ButterKnife.bind as connect
            class KotlinAlias {
                @field:Bind(R.id.title) var title: android.view.View? = null
                fun create(root: android.view.View) { val handle = connect(this, root); handle.unbind() }
            }
        """)
        compileMixed(mixedPlan(source), "KotlinAlias s=new KotlinAlias(); android.view.View root=new android.view.View(); root.children.put(R.id.title,new android.view.View()); s.create(root); if(s.getTitle()!=null) throw new AssertionError();")
    }
    fun testKotlinAdapterRowCallbackReceivesRowAndOtherListenersCompile() {
        val source = kotlinFile("KotlinEvents", """package sample
            import butterknife.*
            class KotlinEvents {
                var received: android.view.View? = null
                @OnItemClick(R.id.title) fun item(view: android.view.View) { received = view }
                @OnItemLongClick(R.id.title) fun longItem(position: Int): Boolean = true
                @OnItemSelected(R.id.title) fun selected(position: Int) {}
                @OnFocusChange(R.id.submit) fun focus(focused: Boolean) {}
                @OnTouch(R.id.submit) fun touch(event: android.view.MotionEvent): Boolean = false
                @OnCheckedChanged(R.id.submit) fun checked(yes: Boolean) {}
                @OnEditorAction(R.id.submit) fun editor(action: Int, event: android.view.KeyEvent): Boolean = true
                @OnPageChange(R.id.other) fun page(position: Int) {}
                fun create(root: android.view.View) { ButterKnife.bind(this,root) }
            }
        """)
        compileMixed(mixedPlan(source), "KotlinEvents s=new KotlinEvents(); android.view.View root=new android.view.View(), row=new android.view.View(); android.widget.AdapterView<Object> adapter=new android.widget.AdapterView<Object>(); root.children.put(R.id.title,adapter); root.children.put(R.id.submit,new android.widget.CompoundButton()); root.children.put(R.id.other,new androidx.viewpager.widget.ViewPager()); s.create(root); adapter.item.onItemClick(adapter,row,4,7L); if(s.getReceived()!=row) throw new AssertionError();")
    }
    fun testSecondRunDoesNotChangeMigratedFile() {
        val after=valid(plan("@BindView(R.id.title) android.view.View title; void create() { ButterKnife.bind(this); }"))
        assertNull(ButterKnifeMigration.plan(myFixture.configureByText("Screen.java",after) as PsiJavaFile).after)
    }
}

.class Lcom/aclaniakea/colorosporttuning/CardBatteryHooks$5;
.super Lde/robv/android/xposed/XC_MethodHook;
.source "CardBatteryHooks.java"


# annotations
.annotation system Ldalvik/annotation/EnclosingMethod;
    value = Lcom/aclaniakea/colorosporttuning/CardBatteryHooks;->install(Lde/robv/android/xposed/callbacks/XC_LoadPackage$LoadPackageParam;)V
.end annotation

.annotation system Ldalvik/annotation/InnerClass;
    accessFlags = 0x0
    name = null
.end annotation


# direct methods
.method constructor <init>()V
    .registers 1

    .line 142
    invoke-direct {p0}, Lde/robv/android/xposed/XC_MethodHook;-><init>()V

    return-void
.end method


# virtual methods
.method protected beforeHookedMethod(Lde/robv/android/xposed/XC_MethodHook$MethodHookParam;)V
    .registers 3
    .annotation system Ldalvik/annotation/Throws;
        value = {
            Ljava/lang/Throwable;
        }
    .end annotation

    .line 145
    iget-object p1, p1, Lde/robv/android/xposed/XC_MethodHook$MethodHookParam;->thisObject:Ljava/lang/Object;

    # getter for: Lcom/aclaniakea/colorosporttuning/CardBatteryHooks;->topActivity:Landroid/app/Activity;
    invoke-static {}, Lcom/aclaniakea/colorosporttuning/CardBatteryHooks;->access$300()Landroid/app/Activity;

    move-result-object v0

    if-ne p1, v0, :cond_c

    const/4 p1, 0x0

    .line 146
    # setter for: Lcom/aclaniakea/colorosporttuning/CardBatteryHooks;->topActivity:Landroid/app/Activity;
    invoke-static {p1}, Lcom/aclaniakea/colorosporttuning/CardBatteryHooks;->access$302(Landroid/app/Activity;)Landroid/app/Activity;

    :cond_c
    return-void
.end method

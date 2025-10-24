package com.tripleu.updater.transform

import android.content.Context
import android.content.pm.PackageInfo
import android.net.Uri
import android.os.Build
import com.tripleu.updater.data.ui.AppInstalled
import com.tripleu.updater.util.getSignatureSha1
import com.tripleu.updater.util.getSignatureSha256
import com.tripleu.updater.util.name


@Suppress("DEPRECATION")
fun PackageInfo.toAppInstalled(context: Context, ignored: List<String>): AppInstalled {
	val label = name(context)
	val iconRes = applicationInfo?.icon ?: 0
	return AppInstalled(
		label,
		packageName,
		versionName.orEmpty(),
		if (Build.VERSION.SDK_INT >= 28) longVersionCode else versionCode.toLong(),
		iconUri(packageName, iconRes),
		ignored.contains(packageName),
		getSignatureSha1(),
		getSignatureSha256()
	)
}

fun iconUri(packageName: String, id: Int): Uri =
	if (id != 0) Uri.parse("android.resource://$packageName/$id") else Uri.EMPTY

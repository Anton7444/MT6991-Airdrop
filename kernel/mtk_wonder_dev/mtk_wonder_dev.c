// SPDX-License-Identifier: GPL-2.0
#include <linux/module.h>
#include <linux/platform_device.h>
#include <linux/kstrtox.h>
#include <linux/export.h>

#define WONDER_DEV_NAME "vendor_wonder_dev"

static char *priv_addr;
module_param(priv_addr, charp, 0444);
MODULE_PARM_DESC(priv_addr, "Address of gen4m's struct wondertap_priv, in hex; omit to publish the symbol only");

static struct platform_device *wonder_pdev;
static unsigned long long wonder_priv;

void cnss_set_vendor_wonder_priv_data(void *priv)
{
	wonder_priv = (unsigned long long)(unsigned long)priv;
	pr_info("mtk_wonder_dev: vendor published priv %llx\n", wonder_priv);
}
EXPORT_SYMBOL_GPL(cnss_set_vendor_wonder_priv_data);

static int __init mtk_wonder_dev_init(void)
{
	unsigned long long addr = 0;
	int ret;


	if (!priv_addr) {
		pr_info("mtk_wonder_dev: loaded without priv_addr; publishing the symbol only\n");
		return 0;
	}

	if (kstrtoull(priv_addr, 16, &addr) || !addr) {
		pr_err("mtk_wonder_dev: bad priv_addr %s\n", priv_addr);
		return -EINVAL;
	}

	wonder_pdev = platform_device_alloc(WONDER_DEV_NAME, PLATFORM_DEVID_NONE);
	if (!wonder_pdev)
		return -ENOMEM;

	ret = platform_device_add(wonder_pdev);
	if (ret) {
		platform_device_put(wonder_pdev);
		wonder_pdev = NULL;
		return ret;
	}

	platform_set_drvdata(wonder_pdev, (void *)(unsigned long)addr);

	pr_info("mtk_wonder_dev: %s up, priv=%llx\n", WONDER_DEV_NAME, addr);
	return 0;
}

static void __exit mtk_wonder_dev_exit(void)
{
	if (wonder_pdev) {
		platform_set_drvdata(wonder_pdev, NULL);
		platform_device_unregister(wonder_pdev);
		wonder_pdev = NULL;
	}
	pr_info("mtk_wonder_dev: removed\n");
}

module_init(mtk_wonder_dev_init);
module_exit(mtk_wonder_dev_exit);

MODULE_LICENSE("GPL");
MODULE_DESCRIPTION("Platform device plus priv for the MediaTek gen4m wonder backend");

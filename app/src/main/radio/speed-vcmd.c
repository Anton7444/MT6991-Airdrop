
#define _GNU_SOURCE
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <errno.h>
#include <unistd.h>
#include <sys/socket.h>
#include <linux/netlink.h>
#include <linux/genetlink.h>
#include <linux/nl80211.h>
#include <net/if.h>

#ifndef NLA_DATA
#define NLA_DATA(nla) ((void *)((char *)(nla) + NLA_HDRLEN))
#define NLA_OK(nla, len) ((len) >= (int)sizeof(struct nlattr) && 	(nla)->nla_len >= sizeof(struct nlattr) && (nla)->nla_len <= (len))
#define NLA_NEXT(nla, attrlen) ((attrlen) -= NLA_ALIGN((nla)->nla_len), 	(struct nlattr *)(((char *)(nla)) + NLA_ALIGN((nla)->nla_len)))
#endif

#define WONDER_OUI 0x001a11

static int nl_send(int fd, struct nlmsghdr *nlh)
{
	struct sockaddr_nl sa = { .nl_family = AF_NETLINK };
	struct iovec iov = { nlh, nlh->nlmsg_len };
	struct msghdr msg = { &sa, sizeof sa, &iov, 1, NULL, 0, 0 };
	return sendmsg(fd, &msg, 0);
}

static int nl_recv(int fd, char *buf, int len)
{
	struct sockaddr_nl sa;
	struct iovec iov = { buf, len };
	struct msghdr msg = { &sa, sizeof sa, &iov, 1, NULL, 0, 0 };
	return recvmsg(fd, &msg, 0);
}

int main(int argc, char **argv)
{
	if (argc < 3) {
		fprintf(stderr, "usage: %s <ifname> <subcmd> [freq] [bw]\n", argv[0]);
		return 2;
	}
	const char *ifname = argv[1];
	int subcmd = atoi(argv[2]);
	unsigned int freq = argc > 3 ? (unsigned)atoi(argv[3]) : 2437;
	unsigned int bw = argc > 4 ? (unsigned)atoi(argv[4]) : 0;

	int fd = socket(AF_NETLINK, SOCK_RAW, NETLINK_GENERIC);
	if (fd < 0) { perror("socket"); return 1; }

	{
		char buf[4096];
		struct nlmsghdr *nlh = (void *)buf;
		struct genlmsghdr *gh;
		struct nlattr *na;
		memset(buf, 0, sizeof buf);
		nlh->nlmsg_len = NLMSG_LENGTH(GENL_HDRLEN);
		nlh->nlmsg_type = GENL_ID_CTRL;
		nlh->nlmsg_flags = NLM_F_REQUEST;
		nlh->nlmsg_seq = 1;
		gh = NLMSG_DATA(nlh);
		gh->cmd = CTRL_CMD_GETFAMILY;
		gh->version = 1;
		na = (void *)((char *)gh + GENL_HDRLEN);
		na->nla_type = CTRL_ATTR_FAMILY_NAME;
		na->nla_len = NLA_HDRLEN + strlen(NL80211_GENL_NAME) + 1;
		strcpy((char *)NLA_DATA(na), NL80211_GENL_NAME);
		nlh->nlmsg_len = NLMSG_ALIGN(nlh->nlmsg_len) + NLA_ALIGN(na->nla_len);
		if (nl_send(fd, nlh) < 0) { perror("send ctrl"); return 1; }
		int n = nl_recv(fd, buf, sizeof buf);
		if (n < 0) { perror("recv ctrl"); return 1; }
		int fam = -1;
		for (nlh = (void *)buf; NLMSG_OK(nlh, n); nlh = NLMSG_NEXT(nlh, n)) {
			gh = NLMSG_DATA(nlh);
			int rem = nlh->nlmsg_len - NLMSG_LENGTH(GENL_HDRLEN);
			for (na = (void *)((char *)gh + GENL_HDRLEN); NLA_OK(na, rem); na = NLA_NEXT(na, rem)) {
				if (na->nla_type == CTRL_ATTR_FAMILY_ID)
					fam = *(unsigned short *)NLA_DATA(na);
			}
		}
		if (fam < 0) { fprintf(stderr, "nl80211 family not found\n"); return 1; }
		fprintf(stderr, "nl80211 family=%d\n", fam);

		memset(buf, 0, sizeof buf);
		nlh = (void *)buf;
		nlh->nlmsg_len = NLMSG_LENGTH(GENL_HDRLEN);
		nlh->nlmsg_type = fam;
		nlh->nlmsg_flags = NLM_F_REQUEST | NLM_F_ACK;
		nlh->nlmsg_seq = 2;
		gh = NLMSG_DATA(nlh);
		gh->cmd = NL80211_CMD_VENDOR;
		gh->version = 0;

		struct nlattr *attrs = (void *)((char *)gh + GENL_HDRLEN);
		int alen = 0;
#define PUT(type, data, dlen) do { \
	struct nlattr *a = (void *)((char *)attrs + alen); \
	a->nla_type = type; a->nla_len = NLA_HDRLEN + (dlen); \
	memcpy(NLA_DATA(a), data, dlen); \
	alen += NLA_ALIGN(a->nla_len); } while (0)

		unsigned int ifidx = if_nametoindex(ifname);
		if (!ifidx) { perror("if_nametoindex"); return 1; }
		PUT(NL80211_ATTR_IFINDEX, &ifidx, 4);
		unsigned int oui = WONDER_OUI;
		PUT(NL80211_ATTR_VENDOR_ID, &oui, 4);
		unsigned int sc = subcmd;
		PUT(NL80211_ATTR_VENDOR_SUBCMD, &sc, 4);

		if (subcmd == 1) {
			struct nlattr *nest = (void *)((char *)attrs + alen);
			nest->nla_type = NL80211_ATTR_VENDOR_DATA;
			int nlen = 0;
			struct nlattr *a;
			a = (void *)((char *)NLA_DATA(nest) + nlen);
			a->nla_type = 1; a->nla_len = NLA_HDRLEN + 4;
			memcpy(NLA_DATA(a), &freq, 4); nlen += NLA_ALIGN(a->nla_len);
			unsigned short bws = bw;
			a = (void *)((char *)NLA_DATA(nest) + nlen);
			a->nla_type = 2; a->nla_len = NLA_HDRLEN + 2;
			memcpy(NLA_DATA(a), &bws, 2); nlen += NLA_ALIGN(a->nla_len);
			nest->nla_len = NLA_HDRLEN + nlen;
			alen += NLA_ALIGN(nest->nla_len);
		}

		if (subcmd == 2) {
			struct nlattr *nest = (void *)((char *)attrs + alen);
			nest->nla_type = NL80211_ATTR_VENDOR_DATA;
			int nlen = 0;
			struct nlattr *a;
			unsigned int ftype = 0;
			a = (void *)((char *)NLA_DATA(nest) + nlen);
			a->nla_type = 1; a->nla_len = NLA_HDRLEN + 4;
			memcpy(NLA_DATA(a), &ftype, 4); nlen += NLA_ALIGN(a->nla_len);

			struct nlattr *params = (void *)((char *)NLA_DATA(nest) + nlen);
			params->nla_type = 2 | NLA_F_NESTED;
			int plen = 0;
			unsigned char enabled = 1;
			a = (void *)((char *)NLA_DATA(params) + plen);
			a->nla_type = 1; a->nla_len = NLA_HDRLEN + 1;
			memcpy(NLA_DATA(a), &enabled, 1); plen += NLA_ALIGN(a->nla_len);
			unsigned char bssid[6] = {0xff,0xff,0xff,0xff,0xff,0xff};
			a = (void *)((char *)NLA_DATA(params) + plen);
			a->nla_type = 2; a->nla_len = NLA_HDRLEN + 6;
			memcpy(NLA_DATA(a), bssid, 6); plen += NLA_ALIGN(a->nla_len);
			params->nla_len = NLA_HDRLEN + plen;
			nlen += NLA_ALIGN(params->nla_len);

			nest->nla_len = NLA_HDRLEN + nlen;
			alen += NLA_ALIGN(nest->nla_len);
		}

		if (subcmd == 3) {
			struct nlattr *nest = (void *)((char *)attrs + alen);
			nest->nla_type = NL80211_ATTR_VENDOR_DATA;
			int nlen = 0;
			struct nlattr *a;
			unsigned int pre = argc > 3 ? (unsigned)atoi(argv[3]) : 1;
			a = (void *)((char *)NLA_DATA(nest) + nlen);
			a->nla_type = 1; a->nla_len = NLA_HDRLEN + 4;
			memcpy(NLA_DATA(a), &pre, 4); nlen += NLA_ALIGN(a->nla_len);
			unsigned short rbw = 0;
			a = (void *)((char *)NLA_DATA(nest) + nlen);
			a->nla_type = 2; a->nla_len = NLA_HDRLEN + 2;
			memcpy(NLA_DATA(a), &rbw, 2); nlen += NLA_ALIGN(a->nla_len);
			unsigned int gi = argc > 4 ? (unsigned)atoi(argv[4]) : 0;
			a = (void *)((char *)NLA_DATA(nest) + nlen);
			a->nla_type = 3; a->nla_len = NLA_HDRLEN + 4;
			memcpy(NLA_DATA(a), &gi, 4); nlen += NLA_ALIGN(a->nla_len);
			unsigned char nss = argc > 6 ? (unsigned char)atoi(argv[6]) : 1;
			a = (void *)((char *)NLA_DATA(nest) + nlen);
			a->nla_type = 4; a->nla_len = NLA_HDRLEN + 1;
			memcpy(NLA_DATA(a), &nss, 1); nlen += NLA_ALIGN(a->nla_len);
			unsigned char mcs = argc > 5 ? (unsigned char)atoi(argv[5]) : 7;
			a = (void *)((char *)NLA_DATA(nest) + nlen);
			a->nla_type = 5; a->nla_len = NLA_HDRLEN + 1;
			memcpy(NLA_DATA(a), &mcs, 1); nlen += NLA_ALIGN(a->nla_len);
			nest->nla_len = NLA_HDRLEN + nlen;
			alen += NLA_ALIGN(nest->nla_len);
		}

		if (subcmd == 4) {
			struct nlattr *nest = (void *)((char *)attrs + alen);
			nest->nla_type = NL80211_ATTR_VENDOR_DATA;
			int nlen = 0;
			struct nlattr *a = (void *)((char *)NLA_DATA(nest) + nlen);
			const char cc[3] = "00";
			a->nla_type = 1; a->nla_len = NLA_HDRLEN + 3;
			memcpy(NLA_DATA(a), cc, 3); nlen += NLA_ALIGN(a->nla_len);
			nest->nla_len = NLA_HDRLEN + nlen;
			alen += NLA_ALIGN(nest->nla_len);
		}

		nlh->nlmsg_len = NLMSG_LENGTH(GENL_HDRLEN) + alen;
		if (nl_send(fd, nlh) < 0) { perror("send vendor"); return 1; }

		n = nl_recv(fd, buf, sizeof buf);
		if (n < 0) { perror("recv vendor"); return 1; }
		for (nlh = (void *)buf; NLMSG_OK(nlh, n); nlh = NLMSG_NEXT(nlh, n)) {
			if (nlh->nlmsg_type == NLMSG_ERROR) {
				struct nlmsgerr *err = NLMSG_DATA(nlh);
				printf("subcmd %d -> %s (%d)\n", subcmd,
				       err->error ? strerror(-err->error) : "OK", err->error);
				return err->error ? 1 : 0;
			}
		}
		printf("subcmd %d -> no error reply\n", subcmd);
	}
	return 0;
}

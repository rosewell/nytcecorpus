package org.ibmt.corpus.ce.nyt;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;

import org.jsoup.HttpStatusException;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

public class WebPageFetcher {
	public static final String CN_NYTIMES_HOMEPAGE = "http://cn.nytimes.com";
	public static final String ID_MAIN_NAV = "mainNav";
	public static final String ID_MAIN = "main";
	public static final String ID_LIST = "columnAB";
	public static final String PATH_DUAL = "dual/";
	public static final String PATH_TOP = "/home/luoch/corpus/ce/nyt";
	public static final int MIN_LIST_ITEM_NUM = 5;
	public static final int SLEEPING_INTERVAL = 500;
	public static final int MAX_RETRY_TIMES = 3;

	private static int retryTimes = 0;
	private static int total = 0;

	/**
	 * @param args
	 */
	public static void main(String[] args) {
		final Document doc;
		try {
			doc = Jsoup.connect(CN_NYTIMES_HOMEPAGE).get();
		} catch (IOException e) {
			e.printStackTrace();
			return;
		}

		final Element mainNav = doc.getElementById(ID_MAIN_NAV);
		if (null == mainNav)
			return;

		final Elements navItems = mainNav.children();
		for (int i = navItems.size(); 0 < --i; ) {
			final Element item = navItems.get(i).child(0);
			if ("a".equalsIgnoreCase(item.tagName()) && item.hasAttr("href")) {
				final String itemPath = item.attr("href");
				if (!itemPath.isEmpty())
					System.out.printf("%4d %s\n", fetch(itemPath),
							itemPath.substring(9, itemPath.length()-1));
			}
		}

		System.out.printf("%4d Total\n", total);
	}

	private static int fetch(final String path) {
		final Document doc;
		try {
			doc = Jsoup.connect(CN_NYTIMES_HOMEPAGE+path).get();
		} catch (IOException e) {
			e.printStackTrace();
			return 0;
		}

		final Element main = doc.getElementById(ID_MAIN);
		if (null == main)
			return 0;

		final Element list = main.getElementById(ID_LIST);
		if (null == list || 2 > list.children().size())
			return 0; 

		int counter = 0;
		for (final Element listItem: list.child(1).children()) {
			final Elements fields = listItem.children();
			if (MIN_LIST_ITEM_NUM > fields.size())
				continue;

			String url = null, summary = null;
			for (final Element field: fields)
				if (field.hasAttr("class"))
					switch (field.attr("class")) {
					case "kicker":
						break;
					case "SFheadline":
						if (1 > field.children().size())
							return 0;
						final Element item = field.child(0);
						if (!"a".equalsIgnoreCase(item.tagName())
								|| !item.hasAttr("href"))
							return 0;
						url = item.attr("href");
						break;
					case "byline":
						break;
					case "summary":
						summary = field.text().trim();
						break;
					case "refer":
						break;
					default:
					}
			if (null != url && !url.isEmpty())
				counter += fetch(url, summary);
		}

		total += counter;
		return counter;
	}
	private static int fetch(final String path, final String summary) {
		final int beg = path.indexOf('/', 1);	// trim the starting '/article'
		final String file = PATH_TOP + (path.endsWith("/")
										? path.substring(beg,path.length()-1)
										: path.substring(beg));
		if (fileExist(file))
			return 1;
		// 404 NotFound URL
		if (dirsExist(file))
			return 0;

		// need NOT sleep when file exist, so move the following sleeping block here from the upper fetch()
		try {
			Thread.sleep(SLEEPING_INTERVAL);
		} catch (InterruptedException e) {
			e.printStackTrace();
		}

		final Document doc;
		try {
			doc = Jsoup.connect(CN_NYTIMES_HOMEPAGE+path+PATH_DUAL).get();
		} catch (HttpStatusException e) {
			final int status = e.getStatusCode();
			System.err.println("\n" + status + ' ' + path.substring(9));
			if (404 == status && !createParentDirs(file))
				System.err.println("Cannot createParentDirs for " + file
						+ ", whose status is NotFound.");
			return 0;
		} catch (SocketTimeoutException e) {
			e.printStackTrace();
			if (++retryTimes > MAX_RETRY_TIMES)
				System.exit(1);
			return 0;
		} catch (IOException e) {
			e.printStackTrace();
			return 0;
		}

		final Element main = doc.getElementById(ID_MAIN);
		if (null == main || 2 > main.children().size())
			return 0;

		final Element layout = main.child(1);
		if (null == layout || 2 > layout.children().size())
			return 0;

		final List<String[]> hs = new ArrayList<String[]>(),
							 ps = new ArrayList<String[]>();

		for (final Element para: layout.child(1).children())
			if ("div".equalsIgnoreCase(para.tagName())
			 && para.hasAttr("class")) {
				final String paraClassAttr = para.attr("class");
				if (paraClassAttr.startsWith("article")) {
					final Elements divs = para.children();
					if (2 == divs.size()) {
						final Element en = divs.get(0), 
								ch = divs.get(1);
						switch (paraClassAttr.substring(7)) {
						case "Content":
							if (ch.hasAttr("class") && en.hasAttr("class")
									&& "chinese".equalsIgnoreCase(ch.attr("class"))
									&& "english".equalsIgnoreCase(en.attr("class"))
									&& "div".equalsIgnoreCase(ch.tagName())
									&& "div".equalsIgnoreCase(en.tagName())) {
								final String[] pair = new String[2];
								pair[0] = ch.text().trim();
								pair[1] = en.text().trim();
								if (!pair[0].isEmpty() && !pair[1].isEmpty())
									ps.add(pair);
							}
							break;
						case "Title":
							final int m = divs.get(0).children().size(),
									  n = divs.get(1).children().size();
							if (m == n)
								for (int i=0; i<n; ++i) {
									final Element e = en.child(i),
												  c = ch.child(i);
									if (e.hasAttr("class") && c.hasAttr("class")
									 && e.attr("class").equals(c.attr("class"))) {
										final String[] pair = new String[2];
										switch (e.attr("class")) {
										case "kicker":
										case "articleHeadline":
											pair[0] = c.text().trim();
											pair[1] = e.text().trim();
											break;
										case "byline":
											break;
										default:
										}
										if (null != pair[0] && !pair[0].isEmpty()
										 && null != pair[1] && !pair[1].isEmpty())
											hs.add(pair);
									}
								}
							break;
						case "Image":
							break;
						default:
						}
					}
				}
			}

		return !ps.isEmpty() && !hs.isEmpty() && save(file,hs,ps) ? 1 : 0;
	}

	private static boolean save(final String filename, final List<String[]> headers,
			final List<String[]> paragraphes) {
		final StringBuffer sb = new StringBuffer();
		for (final String[] pair: headers)
			sb.append(pair[0] + "\t" + pair[1] + "\n");
		sb.append("\n");
		for (final String[] pair: paragraphes)
			sb.append(pair[0] + "\n" + pair[1] + "\n");
		return save(filename, sb.toString());
	}
	private static boolean save(final String fileName, final String content) {
		if (!createParentDirs(fileName))
			return false;

		final File file = new File(fileName);
		try {
			file.createNewFile();
		} catch (IOException e) {
			e.printStackTrace();
			return false;
		}

		Writer writer = null;
		try {
			writer = new BufferedWriter(new FileWriter(file));
			writer.write(content);
		} catch (IOException e) {
			e.printStackTrace();
			return false;
		} finally {
			try {
				if (writer != null)
					writer.close();
			} catch (IOException e) {
				e.printStackTrace();
				return false;
			}
		}

		return true;
	}

	private static boolean createParentDirs(final String path) {
		final int pos = path.lastIndexOf('/');
		if (1 > pos)
			return true;

		final File file = new File(path.substring(0,pos));
		try {
			file.mkdirs();
		} catch(SecurityException e) {
			e.printStackTrace();
			return false;
		}

		return true;
	}

	private static boolean fileExist(final String path) {
		final File file = new File(path);
		return file.exists() && file.isFile() && 0<file.length();
	}
	private static boolean dirsExist(final String path) {
		final int pos = path.lastIndexOf('/');
		if (1 > pos)
			return true;

		final File file = new File(path.substring(0,pos));
		return file.exists() && file.isDirectory();
	}
}

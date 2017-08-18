package jmstool.controller;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import javax.annotation.Resource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import jmstool.QueueManager;
import jmstool.jms.AsyncMessageSender;
import jmstool.jms.AsyncMessageSender.Stats;
import jmstool.model.MessageType;
import jmstool.model.SimpleMessage;
import jmstool.storage.LocalMessageStorage;

/**
 * REST controllers for the web app.
 *
 */
@RestController
public class ApiController {
	private final Logger logger = LoggerFactory.getLogger(this.getClass());

	@Autowired
	private AsyncMessageSender messageSender;

	@Autowired
	private QueueManager queueManager;

	@Resource(name = "outgoingStorage")
	private LocalMessageStorage outgoingStorage;

	@Resource(name = "incomingStorage")
	private LocalMessageStorage incomingLocalStorage;

	@Value("${jmstool.userMessageProperties:}")
	private List<String> userMessageProperties = new ArrayList<>();

	@GetMapping("/api/messages")
	public List<SimpleMessage> getMessages(@RequestParam MessageType messageType, @RequestParam long lastId,
			@RequestParam int maxCount) {

		Collection<SimpleMessage> result;
		switch (messageType) {
		case INCOMING:
			result = incomingLocalStorage.getMessagesAfter(lastId);
			break;
		case OUTGOING:
			result = outgoingStorage.getMessagesAfter(lastId);
			break;
		default:
			throw new IllegalStateException("unknown message type: " + messageType);
		}

		// sort and limit
		return result.stream().sorted(Collections.reverseOrder()).limit(maxCount).collect(Collectors.toList());
	}

	@GetMapping("/api/properties")
	public List<String> getNewMessageProperties() {
		return userMessageProperties;
	}

	@PostMapping("/api/send")
	public void sendMessage(@RequestParam Optional<Integer> count, @RequestBody SimpleMessage message) {

		final int total = count.orElse(1);
		for (int i = 0; i < total; i++) {
			logger.debug("sending new message '{}' to queue '{}' with props '{}' count {}/{} ", message.getText(),
					message.getQueue(), message.getProps(), i + 1, total);
			messageSender.send(message);
		}

	}

	@GetMapping(value = "/api/queues")
	public List<String> getOutgoingQueues() {
		return queueManager.getOutgoingQueues();
	}

	@PostMapping(path = "/api/bulkFile", consumes = { "multipart/*" })
	public @ResponseBody Map<String, String> bulkSend(@RequestParam("file") MultipartFile file,
			@RequestParam("queue") String queue) throws IOException, InterruptedException {
		Path tempFile = Files.createTempFile(file.getOriginalFilename(), null, new FileAttribute[0]);
		Files.write(tempFile, file.getBytes(), new OpenOption[0]);

		logger.debug("processing archive file '{}', queue '{}'", file.getOriginalFilename(), queue);

		final AtomicInteger count = new AtomicInteger();
		for (Path path : FileSystems.newFileSystem(tempFile, null).getRootDirectories()) {
			Files.walk(path).filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
					.peek(p -> logger.debug("iterating over file in archive '{}'", p))
					.peek(p -> count.incrementAndGet())
					.forEach(p -> messageSender.send(createSimpleMessageFromPath(p, queue)));
		}

		Files.delete(tempFile);

		return Collections.singletonMap("count", Integer.toString(count.get()));
	}

	@GetMapping("/api/workInProgress")
	public Stats serverWorkInProgress() {
		return messageSender.getStats();
	}

	private SimpleMessage createSimpleMessageFromPath(Path path, String queue) {
		try {
			byte[] raw = Files.readAllBytes(path);
			String message = new String(raw, Charset.forName("UTF-8"));
			return new SimpleMessage(message, queue);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}
}

package org.klomp.snark;

import java.io.File;
import java.net.URI;
import java.util.List;

import net.i2p.I2PAppContext;
import net.i2p.crypto.TrustedUpdate;
import net.i2p.data.DataHelper;
import net.i2p.update.*;
import net.i2p.util.Log;
import net.i2p.util.SimpleTimer2;
import net.i2p.util.VersionComparator;

/**
 *  The downloader for router signed updates.
 *
 *  @since 0.9.4
 */
class UpdateRunner implements UpdateTask, CompleteListener {
    private final I2PAppContext _context;
    private final Log _log;
    private final UpdateManager _umgr;
    private final SnarkManager _smgr;
    private final List<URI> _urls;
    private volatile boolean _isRunning;
    private volatile boolean _hasMetaInfo;
    private volatile boolean _isComplete;
    private final String _newVersion;
    private URI _currentURI;
    private Snark _snark;

    private static final long MAX_LENGTH = 30*1024*1024;
    private static final long METAINFO_TIMEOUT = 30*60*1000;
    private static final long COMPLETE_TIMEOUT = 3*60*60*1000;

    public UpdateRunner(I2PAppContext ctx, UpdateManager umgr, SnarkManager smgr,
                        List<URI> uris, String newVersion) { 
        _context = ctx;
        _log = ctx.logManager().getLog(getClass());
        _umgr = umgr;
        _smgr = smgr;
        _urls = uris;
        _newVersion = newVersion;
    }

    //////// begin UpdateTask methods

    public boolean isRunning() { return _isRunning; }

    public void shutdown() {
        _isRunning = false;
        if (_snark != null) {

        }
    }

    public UpdateType getType() { return UpdateType.ROUTER_SIGNED; }

    public UpdateMethod getMethod() { return UpdateMethod.TORRENT; }

    public URI getURI() { return _currentURI; }

    public String getID() { return ""; }

    //////// end UpdateTask methods

    public void start() {
        _isRunning = true;
        update();
    }

    /**
     *  Loop through the entire list of update URLs.
     *  For each one, first get the version from the first 56 bytes and see if
     *  it is newer than what we are running now.
     *  If it is, get the whole thing.
     */
    private void update() {
        for (URI uri : _urls) {
            _currentURI = uri;
            String updateURL = uri.toString();
            try {
                MagnetURI magnet = new MagnetURI(_smgr.util(), updateURL);
                String name = magnet.getName();
                byte[] ih = magnet.getInfoHash();
                String trackerURL = magnet.getTrackerURL();
                if (trackerURL == null && !_smgr.util().shouldUseDHT() &&
                    !_smgr.util().shouldUseOpenTrackers()) {
                    // but won't we use OT as a failsafe even if disabled?
                    _umgr.notifyAttemptFailed(this, "No tracker, no DHT, no OT", null);
                    continue;
                }
                _snark = _smgr.addMagnet(name, ih, trackerURL, true, true, this);
                if (_snark != null) {
                    updateStatus("<b>" + _smgr.util().getString("Updating from {0}", updateURL) + "</b>");
                    new Timeout();
                    break;
                }
            } catch (IllegalArgumentException iae) {
                _log.error("Invalid update URL", iae);
            }
        }
        if (_snark == null)
            fatal("No valid URLs");
    }

    /**
     *  This will run twice, once at the metainfo timeout and
     *  once at the complete timeout.
     */
    private class Timeout extends SimpleTimer2.TimedEvent {
        private final long _start = _context.clock().now();

        public Timeout() {
            super(_context.simpleTimer2(), METAINFO_TIMEOUT);
        }

        public void timeReached() {
            if (_isComplete || !_isRunning)
                return;
            if (!_hasMetaInfo) {
                fatal("Metainfo timeout");
                return;
            }
            if (_context.clock().now() - _start >= COMPLETE_TIMEOUT) {
                fatal("Complete timeout");
                return;
            }
            reschedule(COMPLETE_TIMEOUT - METAINFO_TIMEOUT);
        }
    }

    private void fatal(String error) {
            if (_snark != null) {
                if (_hasMetaInfo) {
                    _smgr.stopTorrent(_snark, true);
                    String file = _snark.getName();
                    _smgr.removeTorrent(file);
                    // delete torrent file
                    File f = new File(_smgr.getDataDir(), file);
                    f.delete();
                    // delete data
                    file = _snark.getBaseName();
                    f = new File(_smgr.getDataDir(), file);
                    f.delete();
                } else {
                    _smgr.deleteMagnet(_snark);
                }
            }
            _umgr.notifyTaskFailed(this, error, null);
            _log.error(error);
            _isRunning = false;
    }

    //////// begin CompleteListener methods
    //////// all pass through to SnarkManager

    public void torrentComplete(Snark snark) {
        String dataFile = snark.getBaseName();
        File f = new File(_smgr.getDataDir(), dataFile);
        String sudVersion = TrustedUpdate.getVersionString(f);
        if (!_newVersion.equals(sudVersion)) {
            fatal("version mismatch");
        }
        _umgr.notifyComplete(this, _newVersion, f);
        _smgr.torrentComplete(snark);
        _isComplete = true;
    }

    /**
     *  This is called by stopTorrent() among others
     */
    public void updateStatus(Snark snark) {
        if (snark.isStopped()) {
            if (!_isComplete)
                fatal("stopped by user");
        }
        _smgr.updateStatus(snark);
    }

    public String gotMetaInfo(Snark snark) {
        MetaInfo info = snark.getMetaInfo();
        if (info.getFiles() != null) {
            fatal("more than 1 file");
            return null;
        }
        if (info.isPrivate()) {
            fatal("private torrent");
            return null;
        }
        if (info.getTotalLength() > MAX_LENGTH) {
            fatal("too big");
            return null;
        }
        _hasMetaInfo = true;
        return _smgr.gotMetaInfo(snark);
    }

    public void fatal(Snark snark, String error) {
         fatal(error);
        _smgr.fatal(snark, error);
    }

    public void addMessage(Snark snark, String message) {
        _smgr.addMessage(snark, message);
    }

    public void gotPiece(Snark snark) {
        if (_hasMetaInfo) {
            long total = snark.getTotalLength();
            long remaining = snark.getRemainingLength(); 
            String status = "<b>" + _smgr.util().getString("Updating") + "</b>";
            _umgr.notifyProgress(this, status, total - remaining, total);
        }
        _smgr.gotPiece(snark);
    }

    public long getSavedTorrentTime(Snark snark) {
        return _smgr.getSavedTorrentTime(snark);
    }

    public BitField getSavedTorrentBitField(Snark snark) {
        return _smgr.getSavedTorrentBitField(snark);
    }

    //////// end CompleteListener methods

    private void updateStatus(String s) {
        _umgr.notifyProgress(this, s);
    }

    @Override
    public String toString() {
        return getClass().getName() + ' ' + getType() + ' ' + getID() + ' ' + getMethod() + ' ' + getURI();
    }
}